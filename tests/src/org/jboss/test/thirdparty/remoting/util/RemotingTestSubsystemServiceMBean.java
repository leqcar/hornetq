/**
 * JBoss, Home of Professional Open Source
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jboss.test.thirdparty.remoting.util;

import org.jboss.remoting.InvocationRequest;

/**
 * @author <a href="mailto:ovidiu@jboss.org">Ovidiu Feodorov</a>
 * @version <tt>$Revision$</tt>
 * $Id$
 */
public interface RemotingTestSubsystemServiceMBean
{
   void start() throws Exception;
   void stop();

   InvocationRequest nextInvocation(Long timeout) throws Exception;
   
   boolean isFailed() throws Exception;
}
