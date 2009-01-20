/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005-2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.messaging.core.server.cluster.impl;

import java.util.LinkedList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.jboss.messaging.core.client.ClientProducer;
import org.jboss.messaging.core.client.ClientSession;
import org.jboss.messaging.core.client.ClientSessionFactory;
import org.jboss.messaging.core.client.impl.ClientSessionFactoryImpl;
import org.jboss.messaging.core.client.impl.ClientSessionImpl;
import org.jboss.messaging.core.config.TransportConfiguration;
import org.jboss.messaging.core.exception.MessagingException;
import org.jboss.messaging.core.filter.Filter;
import org.jboss.messaging.core.filter.impl.FilterImpl;
import org.jboss.messaging.core.logging.Logger;
import org.jboss.messaging.core.message.impl.MessageImpl;
import org.jboss.messaging.core.persistence.StorageManager;
import org.jboss.messaging.core.remoting.FailureListener;
import org.jboss.messaging.core.remoting.RemotingConnection;
import org.jboss.messaging.core.server.HandleStatus;
import org.jboss.messaging.core.server.MessageReference;
import org.jboss.messaging.core.server.Queue;
import org.jboss.messaging.core.server.ServerMessage;
import org.jboss.messaging.core.server.cluster.Bridge;
import org.jboss.messaging.core.server.cluster.Transformer;
import org.jboss.messaging.core.transaction.Transaction;
import org.jboss.messaging.core.transaction.impl.TransactionImpl;
import org.jboss.messaging.util.Future;
import org.jboss.messaging.util.Pair;
import org.jboss.messaging.util.SimpleString;

/**
 * A BridgeImpl
 *
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * 
 * Created 12 Nov 2008 11:37:35
 *
 *
 */
public class BridgeImpl implements Bridge, FailureListener
{
   // Constants -----------------------------------------------------

   private static final Logger log = Logger.getLogger(BridgeImpl.class);

   // Attributes ----------------------------------------------------

   private final SimpleString name;

   private final Queue queue;

   private final Executor executor;

   private volatile boolean busy;

   private final int maxBatchSize;

   private final long maxBatchTime;

   private final SimpleString filterString;

   private final Filter filter;

   private final SimpleString forwardingAddress;

   private int count;

   private final java.util.Queue<MessageReference> refs = new LinkedList<MessageReference>();

   private Transaction tx;

   private long lastReceivedTime = -1;

   private final StorageManager storageManager;

   private final Transformer transformer;

   private final ClientSessionFactory csf;

   private ClientSession session;

   private ClientProducer producer;

   private volatile boolean started;

   private final ScheduledFuture<?> future;

   private final boolean useDuplicateDetection;

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   public BridgeImpl(final SimpleString name,
                     final Queue queue,
                     final Pair<TransportConfiguration, TransportConfiguration> connectorPair,
                     final Executor executor,
                     final int maxBatchSize,
                     final long maxBatchTime,
                     final SimpleString filterString,
                     final SimpleString forwardingAddress,
                     final StorageManager storageManager,
                     final ScheduledExecutorService scheduledExecutor,
                     final Transformer transformer,
                     final long retryInterval,
                     final double retryIntervalMultiplier,
                     final int maxRetriesBeforeFailover,
                     final int maxRetriesAfterFailover,                 
                     final boolean useDuplicateDetection) throws Exception
   {
      this.name = name;

      this.queue = queue;

      this.executor = executor;

      this.maxBatchSize = maxBatchSize;

      this.maxBatchTime = maxBatchTime;

      this.filterString = filterString;

      if (this.filterString != null)
      {
         this.filter = new FilterImpl(filterString);
      }
      else
      {
         this.filter = null;
      }

      this.forwardingAddress = forwardingAddress;

      this.storageManager = storageManager;

      this.transformer = transformer;

      this.useDuplicateDetection = useDuplicateDetection;

      this.csf = new ClientSessionFactoryImpl(connectorPair.a,
                                              connectorPair.b,
                                              retryInterval,
                                              retryIntervalMultiplier,
                                              maxRetriesBeforeFailover,
                                              maxRetriesAfterFailover);

      if (maxBatchTime != -1)
      {
         future = scheduledExecutor.scheduleAtFixedRate(new BatchTimeout(),
                                                        maxBatchTime,
                                                        maxBatchTime,
                                                        TimeUnit.MILLISECONDS);
      }
      else
      {
         future = null;
      }
   }

   public synchronized void start() throws Exception
   {
      if (started)
      {
         return;
      }

      queue.addConsumer(this);

      createTx();

      if (createObjects())
      {
         started = true;

         queue.deliverAsync(executor);
      }
   }

   public synchronized void stop() throws Exception
   {
      started = false;

      queue.removeConsumer(this);

      if (future != null)
      {
         future.cancel(false);
      }

      // Wait until all batches are complete

      Future future = new Future();

      executor.execute(future);

      boolean ok = future.await(10000);

      if (!ok)
      {
         log.warn("Timed out waiting for batch to be sent");
      }

      if (session != null)
      {
         session.close();
      }

      started = false;
   }

   public boolean isStarted()
   {
      return started;
   }

   // For testing only
   public RemotingConnection getForwardingConnection()
   {
      return ((ClientSessionImpl)session).getConnection();
   }

   // Consumer implementation ---------------------------------------

   public HandleStatus handle(final MessageReference reference) throws Exception
   {
      if (busy)
      {
         return HandleStatus.BUSY;
      }

      if (filter != null && !filter.match(reference.getMessage()))
      {
         return HandleStatus.NO_MATCH;
      }

      synchronized (this)
      {
         if (!started)
         {
            return HandleStatus.BUSY;
         }

         reference.getQueue().referenceHandled();

         refs.add(reference);

         if (maxBatchTime != -1)
         {
            lastReceivedTime = System.currentTimeMillis();
         }

         count++;

         if (count == maxBatchSize)
         {
            busy = true;

            executor.execute(new BatchSender());
         }

         return HandleStatus.HANDLED;
      }
   }

   // FailureListener implementation --------------------------------

   public synchronized boolean connectionFailed(final MessagingException me)
   {
      // By the time this is called
      synchronized (this)
      {
         try
         {
            session.close();

            createObjects();
         }
         catch (Exception e)
         {
            log.error("Failed to reconnect", e);
         }

         return true;
      }
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   private boolean createObjects() throws Exception
   {
      try
      {
         session = csf.createSession(false, false, false);
      }
      catch (MessagingException me)
      {
         log.warn("Unable to connect. Message flow is now disabled.");

         stop();

         return false;
      }

      session.addFailureListener(this);

      producer = session.createProducer();

      return true;
   }

   private synchronized void timeoutBatch()
   {
      if (!started)
      {
         return;
      }

      if (lastReceivedTime != -1 && count > 0)
      {
         long now = System.currentTimeMillis();

         if (now - lastReceivedTime >= maxBatchTime)
         {
            sendBatch();
         }
      }
   }

   private synchronized void sendBatch()
   {
      try
      {
         if (count == 0)
         {
            return;
         }

         // TODO - if batch size = 1 then don't need tx

         while (true)
         {
            MessageReference ref = refs.poll();

            if (ref == null)
            {
               break;
            }

            ref.getQueue().acknowledge(tx, ref);

            ServerMessage message = ref.getMessage();

            if (transformer != null)
            {
               message = transformer.transform(message);
            }

            SimpleString dest;

            if (forwardingAddress != null)
            {
               dest = forwardingAddress;
            }
            else
            {
               dest = (SimpleString)message.getProperty(MessageImpl.HDR_ORIGINAL_DESTINATION);
            }

            producer.send(dest, message);
         }

         session.commit();

         tx.commit();

         createTx();

         busy = false;

         count = 0;

         queue.deliverAsync(executor);
      }
      catch (Exception e)
      {
         log.error("Failed to forward batch", e);

         try
         {
            tx.rollback();
         }
         catch (Exception e2)
         {
            log.error("Failed to rollback", e2);
         }
      }
   }

   private void createTx()
   {
      tx = new TransactionImpl(storageManager);
   }

   // Inner classes -------------------------------------------------

   private class BatchSender implements Runnable
   {
      public void run()
      {
         sendBatch();
      }
   }

   private class BatchTimeout implements Runnable
   {
      public void run()
      {
         timeoutBatch();
      }
   }

   public SimpleString getName()
   {
      return name;
   }

   public Queue getQueue()
   {
      return queue;
   }

   public int getMaxBatchSize()
   {
      return maxBatchSize;
   }

   public long getMaxBatchTime()
   {
      return maxBatchTime;
   }

   public SimpleString getFilterString()
   {
      return filterString;
   }

   public SimpleString getForwardingAddress()
   {
      return forwardingAddress;
   }

   public Transformer getTransformer()
   {
      return transformer;
   }

   public boolean isUseDuplicateDetection()
   {
      return useDuplicateDetection;
   }

}
