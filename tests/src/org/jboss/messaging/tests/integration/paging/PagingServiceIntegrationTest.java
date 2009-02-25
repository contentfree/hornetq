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

package org.jboss.messaging.tests.integration.paging;

import java.nio.ByteBuffer;
import java.util.HashMap;

import junit.framework.AssertionFailedError;

import org.jboss.messaging.core.client.ClientConsumer;
import org.jboss.messaging.core.client.ClientMessage;
import org.jboss.messaging.core.client.ClientProducer;
import org.jboss.messaging.core.client.ClientSession;
import org.jboss.messaging.core.client.ClientSessionFactory;
import org.jboss.messaging.core.config.Configuration;
import org.jboss.messaging.core.logging.Logger;
import org.jboss.messaging.core.message.impl.MessageImpl;
import org.jboss.messaging.core.remoting.impl.ByteBufferWrapper;
import org.jboss.messaging.core.remoting.spi.MessagingBuffer;
import org.jboss.messaging.core.server.MessagingService;
import org.jboss.messaging.core.server.Queue;
import org.jboss.messaging.core.settings.impl.AddressSettings;
import org.jboss.messaging.tests.util.ServiceTestBase;
import org.jboss.messaging.utils.DataConstants;
import org.jboss.messaging.utils.SimpleString;

/**
 * A PagingServiceIntegrationTest
 *
 * @author <a href="mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 * 
 * Created Dec 5, 2008 8:25:58 PM
 *
 *
 */
public class PagingServiceIntegrationTest extends ServiceTestBase
{

   // Constants -----------------------------------------------------
   private static final Logger log = Logger.getLogger(PagingServiceIntegrationTest.class);

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   static final SimpleString ADDRESS = new SimpleString("SimpleAddress");

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   public void testSendReceivePaging() throws Exception
   {
      clearData();

      Configuration config = createDefaultConfig();

      config.setPagingMaxGlobalSizeBytes(100 * 1024);
      config.setPagingGlobalWatermarkSize(10 * 1024);

      MessagingService messagingService = createService(true, config, new HashMap<String, AddressSettings>());

      messagingService.start();

      final int numberOfIntegers = 256;

      final int numberOfMessages = 10000;

      try
      {
         ClientSessionFactory sf = createInVMFactory();

         sf.setBlockOnNonPersistentSend(true);
         sf.setBlockOnPersistentSend(true);
         sf.setBlockOnAcknowledge(true);

         ClientSession session = sf.createSession(null, null, false, true, true, false, 0);

         session.createQueue(ADDRESS, ADDRESS, null, true, false);

         ClientProducer producer = session.createProducer(ADDRESS);

         ByteBuffer ioBuffer = ByteBuffer.allocate(DataConstants.SIZE_INT * numberOfIntegers);

         ClientMessage message = null;

         MessagingBuffer body = null;

         for (int i = 0; i < numberOfMessages; i++)
         {
            MessagingBuffer bodyLocal = new ByteBufferWrapper(ioBuffer);

            for (int j = 1; j <= numberOfIntegers; j++)
            {
               bodyLocal.putInt(j);
            }
            bodyLocal.flip();

            if (i == 0)
            {
               body = bodyLocal;
            }

            message = session.createClientMessage(true);
            message.setBody(bodyLocal);
            message.putIntProperty(new SimpleString("id"), i);

            producer.send(message);
         }

         session.close();

         messagingService.stop();

         messagingService = createService(true, config, new HashMap<String, AddressSettings>());
         messagingService.start();

         sf = createInVMFactory();

         assertTrue(messagingService.getServer().getPostOffice().getPagingManager().getGlobalSize() > 0);

         session = sf.createSession(null, null, false, true, true, false, 0);

         ClientConsumer consumer = session.createConsumer(ADDRESS);

         session.start();

         for (int i = 0; i < numberOfMessages; i++)
         {
            ClientMessage message2 = consumer.receive(10000);

            assertNotNull(message2);

            assertEquals(i, ((Integer)message2.getProperty(new SimpleString("id"))).intValue());

            message2.acknowledge();

            assertNotNull(message2);

            try
            {
               assertEqualsByteArrays(body.limit(), body.array(), message2.getBody().array());
            }
            catch (AssertionFailedError e)
            {
               log.info("Expected buffer:" + dumbBytesHex(body.array(), 40));
               log.info("Arriving buffer:" + dumbBytesHex(message2.getBody().array(), 40));
               throw e;
            }
         }

         consumer.close();

         session.close();

         assertEquals(0, messagingService.getServer().getPostOffice().getPagingManager().getGlobalSize());

      }
      finally
      {
         try
         {
            messagingService.stop();
         }
         catch (Throwable ignored)
         {
         }
      }

   }

   /**
    * - Make a destination in page mode
    * - Add stuff to a transaction
    * - Consume the entire destination (not in page mode any more)
    * - Add stuff to a transaction again
    * - Check order
    * 
    */
   public void testDepageDuringTransaction() throws Exception
   {
      clearData();

      Configuration config = createDefaultConfig();

      config.setPagingMaxGlobalSizeBytes(100 * 1024);
      config.setPagingGlobalWatermarkSize(10 * 1024);

      MessagingService messagingService = createService(true, config, new HashMap<String, AddressSettings>());

      messagingService.start();

      final int numberOfIntegers = 256;

      try
      {
         ClientSessionFactory sf = createInVMFactory();

         sf.setBlockOnNonPersistentSend(true);
         sf.setBlockOnPersistentSend(true);
         sf.setBlockOnAcknowledge(true);

         ClientSession session = sf.createSession(null, null, false, true, true, false, 0);

         session.createQueue(ADDRESS, ADDRESS, null, true, false);

         ClientProducer producer = session.createProducer(ADDRESS);

         ByteBuffer ioBuffer = ByteBuffer.allocate(DataConstants.SIZE_INT * numberOfIntegers);
         MessagingBuffer bodyLocal = new ByteBufferWrapper(ioBuffer);

         ClientMessage message = null;

         int numberOfMessages = 0;
         while (true)
         {
            message = session.createClientMessage(true);
            message.setBody(bodyLocal);

            // Stop sending message as soon as we start paging
            if (messagingService.getServer().getPostOffice().getPagingManager().isPaging(ADDRESS))
            {
               break;
            }
            numberOfMessages++;

            producer.send(message);
         }

         assertTrue(messagingService.getServer().getPostOffice().getPagingManager().isPaging(ADDRESS));

         session.start();

         ClientSession sessionTransacted = sf.createSession(null, null, false, false, false, false, 0);

         ClientProducer producerTransacted = sessionTransacted.createProducer(ADDRESS);

         for (int i = 0; i < 10; i++)
         {
            message = session.createClientMessage(true);
            message.setBody(bodyLocal);
            message.putIntProperty(new SimpleString("id"), i);

            // Consume messages to force an eventual out of order delivery
            if (i == 5)
            {
               ClientConsumer consumer = session.createConsumer(ADDRESS);
               for (int j = 0; j < numberOfMessages; j++)
               {
                  ClientMessage msg = consumer.receive(1000);
                  msg.acknowledge();
                  assertNotNull(msg);
               }

               assertNull(consumer.receive(100));
               consumer.close();
            }

            Integer messageID = (Integer)message.getProperty(new SimpleString("id"));
            assertNotNull(messageID);
            assertEquals(messageID.intValue(), i);

            producerTransacted.send(message);
         }

         ClientConsumer consumer = session.createConsumer(ADDRESS);

         assertNull(consumer.receive(100));

         sessionTransacted.commit();

         sessionTransacted.close();

         for (int i = 0; i < 10; i++)
         {
            message = consumer.receive(10000);

            assertNotNull(message);

            Integer messageID = (Integer)message.getProperty(new SimpleString("id"));

            assertNotNull(messageID);
            assertEquals("message received out of order", messageID.intValue(), i);

            message.acknowledge();
         }

         assertNull(consumer.receive(100));

         consumer.close();

         session.close();

         assertEquals(0, messagingService.getServer().getPostOffice().getPagingManager().getGlobalSize());

      }
      finally
      {
         try
         {
            messagingService.stop();
         }
         catch (Throwable ignored)
         {
         }
      }

   }

   public void testPageOnSchedulingNoRestart() throws Exception
   {
      internalTestPageOnScheduling(false);
   }

   public void testPageOnSchedulingRestart() throws Exception
   {
      internalTestPageOnScheduling(true);
   }

   public void internalTestPageOnScheduling(final boolean restart) throws Exception
   {
      clearData();

      Configuration config = createDefaultConfig();

      config.setPagingMaxGlobalSizeBytes(100 * 1024);
      config.setPagingGlobalWatermarkSize(10 * 1024);

      MessagingService messagingService = createService(true, config, new HashMap<String, AddressSettings>());

      messagingService.start();

      final int numberOfIntegers = 256;

      final int numberOfMessages = 10000;

      try
      {
         ClientSessionFactory sf = createInVMFactory();

         sf.setBlockOnNonPersistentSend(true);
         sf.setBlockOnPersistentSend(true);
         sf.setBlockOnAcknowledge(true);

         ClientSession session = sf.createSession(null, null, false, true, true, false, 0);

         session.createQueue(ADDRESS, ADDRESS, null, true, false);

         ClientProducer producer = session.createProducer(ADDRESS);

         ByteBuffer ioBuffer = ByteBuffer.allocate(DataConstants.SIZE_INT * numberOfIntegers);

         ClientMessage message = null;

         MessagingBuffer body = null;

         long scheduledTime = System.currentTimeMillis() + 5000;

         for (int i = 0; i < numberOfMessages; i++)
         {
            MessagingBuffer bodyLocal = new ByteBufferWrapper(ioBuffer);

            for (int j = 1; j <= numberOfIntegers; j++)
            {
               bodyLocal.putInt(j);
            }
            bodyLocal.flip();

            if (i == 0)
            {
               body = bodyLocal;
            }
            message = session.createClientMessage(true);
            message.setBody(bodyLocal);
            message.putIntProperty(new SimpleString("id"), i);

            // Worse scenario possible... only schedule what's on pages
            if (messagingService.getServer().getPostOffice().getPagingManager().isPaging(ADDRESS))
            {
               message.putLongProperty(MessageImpl.HDR_SCHEDULED_DELIVERY_TIME, scheduledTime);
            }

            producer.send(message);
         }

         if (restart)
         {
            session.close();

            messagingService.stop();

            messagingService = createService(true, config, new HashMap<String, AddressSettings>());
            messagingService.start();

            sf = createInVMFactory();

            session = sf.createSession(null, null, false, true, true, false, 0);
         }

         ClientConsumer consumer = session.createConsumer(ADDRESS);

         session.start();

         for (int i = 0; i < numberOfMessages; i++)
         {
            ClientMessage message2 = consumer.receive(10000);

            assertNotNull(message2);

            message2.acknowledge();

            assertNotNull(message2);

            Long scheduled = (Long)message2.getProperty(MessageImpl.HDR_SCHEDULED_DELIVERY_TIME);
            if (scheduled != null)
            {
               assertTrue("Scheduling didn't work", System.currentTimeMillis() >= scheduledTime);
            }

            try
            {
               assertEqualsByteArrays(body.limit(), body.array(), message2.getBody().array());
            }
            catch (AssertionFailedError e)
            {
               log.info("Expected buffer:" + dumbBytesHex(body.array(), 40));
               log.info("Arriving buffer:" + dumbBytesHex(message2.getBody().array(), 40));
               throw e;
            }
         }

         consumer.close();

         session.close();

         assertEquals(0, messagingService.getServer().getPostOffice().getPagingManager().getGlobalSize());

      }
      finally
      {
         try
         {
            messagingService.stop();
         }
         catch (Throwable ignored)
         {
         }
      }

   }

   public void testRollbackOnSend() throws Exception
   {
      clearData();

      Configuration config = createDefaultConfig();

      config.setPagingMaxGlobalSizeBytes(100 * 1024);
      config.setPagingGlobalWatermarkSize(10 * 1024);

      MessagingService messagingService = createService(true, config, new HashMap<String, AddressSettings>());

      messagingService.start();

      final int numberOfIntegers = 256;

      final int numberOfMessages = 10;

      try
      {
         ClientSessionFactory sf = createInVMFactory();

         sf.setBlockOnNonPersistentSend(true);
         sf.setBlockOnPersistentSend(true);
         sf.setBlockOnAcknowledge(true);

         ClientSession session = sf.createSession(null, null, false, false, true, false, 0);

         session.createQueue(ADDRESS, ADDRESS, null, true, false);

         ClientProducer producer = session.createProducer(ADDRESS);

         long initialSize = messagingService.getServer().getPostOffice().getPagingManager().getGlobalSize();

         ByteBuffer ioBuffer = ByteBuffer.allocate(DataConstants.SIZE_INT * numberOfIntegers);

         ClientMessage message = null;

         for (int i = 0; i < numberOfMessages; i++)
         {
            MessagingBuffer bodyLocal = new ByteBufferWrapper(ioBuffer);

            for (int j = 1; j <= numberOfIntegers; j++)
            {
               bodyLocal.putInt(j);
            }
            bodyLocal.flip();

            message = session.createClientMessage(true);
            message.setBody(bodyLocal);
            message.putIntProperty(new SimpleString("id"), i);

            producer.send(message);
         }

         session.rollback();

         ClientConsumer consumer = session.createConsumer(ADDRESS);

         session.start();

         assertNull(consumer.receive(500));

         session.close();

         assertEquals(initialSize, messagingService.getServer().getPostOffice().getPagingManager().getGlobalSize());
      }
      finally
      {
         try
         {
            messagingService.stop();
         }
         catch (Throwable ignored)
         {
         }
      }

   }

   public void testCommitOnSend() throws Exception
   {
      clearData();

      Configuration config = createDefaultConfig();

      config.setPagingMaxGlobalSizeBytes(100 * 1024);
      config.setPagingGlobalWatermarkSize(10 * 1024);

      MessagingService messagingService = createService(true, config, new HashMap<String, AddressSettings>());

      messagingService.start();

      final int numberOfIntegers = 10;

      final int numberOfMessages = 10;

      try
      {
         ClientSessionFactory sf = createInVMFactory();

         sf.setBlockOnNonPersistentSend(true);
         sf.setBlockOnPersistentSend(true);
         sf.setBlockOnAcknowledge(true);

         ClientSession session = sf.createSession(null, null, false, false, false, false, 0);

         session.createQueue(ADDRESS, ADDRESS, null, true, false);

         ClientProducer producer = session.createProducer(ADDRESS);

         long initialSize = messagingService.getServer().getPostOffice().getPagingManager().getGlobalSize();

         ByteBuffer ioBuffer = ByteBuffer.allocate(DataConstants.SIZE_INT * numberOfIntegers);

         ClientMessage message = null;

         for (int i = 0; i < numberOfMessages; i++)
         {
            MessagingBuffer bodyLocal = new ByteBufferWrapper(ioBuffer);

            for (int j = 1; j <= numberOfIntegers; j++)
            {
               bodyLocal.putInt(j);
            }
            bodyLocal.flip();

            message = session.createClientMessage(true);
            message.setBody(bodyLocal);
            message.putIntProperty(new SimpleString("id"), i);

            producer.send(message);
         }

         session.commit();

         ClientConsumer consumer = session.createConsumer(ADDRESS);

         session.start();
         for (int i = 0; i < numberOfMessages; i++)
         {
            ClientMessage msg = consumer.receive(500);
            assertNotNull(msg);
            msg.acknowledge();
         }

         session.commit();

         session.close();

         assertEquals(initialSize, messagingService.getServer().getPostOffice().getPagingManager().getGlobalSize());
      }
      finally
      {
         try
         {
            messagingService.stop();
         }
         catch (Throwable ignored)
         {
         }
      }

   }

   public void testPageMultipleDestinations() throws Exception
   {
      internalTestPageMultipleDestinations(false);
   }

   public void testPageMultipleDestinationsTransacted() throws Exception
   {
      internalTestPageMultipleDestinations(true);
   }

   public void testDropMessagesQueueMax() throws Exception
   {
      testDropMessages(false);
   }

   public void testDropMessagesGlobalMax() throws Exception
   {
      testDropMessages(true);
   }

   private void testDropMessages(boolean global) throws Exception
   {
      clearData();

      Configuration config = createDefaultConfig();

      HashMap<String, AddressSettings> settings = new HashMap<String, AddressSettings>();

      AddressSettings set = new AddressSettings();
      set.setDropMessagesWhenFull(true);

      settings.put(ADDRESS.toString(), set);

      if (global)
      {
         set.setMaxSizeBytes(-1);
         config.setPagingMaxGlobalSizeBytes(10 * 1024);
      }
      else
      {
         config.setPagingMaxGlobalSizeBytes(-1);
         set.setMaxSizeBytes(10 * 1024);
      }

      config.setPagingGlobalWatermarkSize(10 * 1024);
 
      MessagingService messagingService = createService(true, config, settings);

      messagingService.start();

      final int sizeOfMessage = 1024;
      final int numberOfMessages = 1000;

      try
      {
         ClientSessionFactory sf = createInVMFactory();

         sf.setBlockOnNonPersistentSend(true);
         sf.setBlockOnPersistentSend(true);
         sf.setBlockOnAcknowledge(true);

         ClientSession session = sf.createSession(null, null, false, true, true, false, 0);

         session.createQueue(ADDRESS, ADDRESS, null, true, false);

         ClientProducer producer = session.createProducer(ADDRESS);

         ByteBuffer ioBuffer = ByteBuffer.allocate(sizeOfMessage);

         ClientMessage message = null;

         for (int i = 0; i < numberOfMessages; i++)
         {
            MessagingBuffer bodyLocal = new ByteBufferWrapper(ioBuffer);

            message = session.createClientMessage(true);
            message.setBody(bodyLocal);

            producer.send(message);
         }

         session = sf.createSession(null, null, false, true, true, false, 0);

         ClientConsumer consumer = session.createConsumer(ADDRESS);

         session.start();

         for (int i = 0; i < 9; i++)
         {
            ClientMessage message2 = consumer.receive(10000);

            assertNotNull(message2);

            message2.acknowledge();
         }

         assertNull(consumer.receive(100));

         assertEquals(0, messagingService.getServer().getPostOffice().getPagingManager().getGlobalSize());
         assertEquals(0, messagingService.getServer()
                                         .getPostOffice()
                                         .getPagingManager()
                                         .getPageStore(ADDRESS)
                                         .getAddressSize());

         for (int i = 0; i < numberOfMessages; i++)
         {
            MessagingBuffer bodyLocal = new ByteBufferWrapper(ioBuffer);

            message = session.createClientMessage(true);
            message.setBody(bodyLocal);

            producer.send(message);
         }

         for (int i = 0; i < 9; i++)
         {
            ClientMessage message2 = consumer.receive(10000);

            assertNotNull(message2);

            message2.acknowledge();
         }

         assertNull(consumer.receive(100));

         session.close();

         session = sf.createSession(false, true, true);

         producer = session.createProducer(ADDRESS);

         for (int i = 0; i < numberOfMessages; i++)
         {
            MessagingBuffer bodyLocal = new ByteBufferWrapper(ioBuffer);

            message = session.createClientMessage(true);
            message.setBody(bodyLocal);

            producer.send(message);
         }

         session.commit();

         consumer = session.createConsumer(ADDRESS);

         session.start();

         for (int i = 0; i < 9; i++)
         {
            ClientMessage message2 = consumer.receive(10000);

            assertNotNull(message2);

            message2.acknowledge();
         }

         session.commit();

         assertNull(consumer.receive(100));

         session.close();

         assertEquals(0, messagingService.getServer().getPostOffice().getPagingManager().getGlobalSize());
         assertEquals(0, messagingService.getServer()
                                         .getPostOffice()
                                         .getPagingManager()
                                         .getPageStore(ADDRESS)
                                         .getAddressSize());

      }
      finally
      {
         try
         {
            messagingService.stop();
         }
         catch (Throwable ignored)
         {
         }
      }

   }

   private void internalTestPageMultipleDestinations(boolean transacted) throws Exception
   {
      Configuration config = createDefaultConfig();

      final int MAX_SIZE = 90 * 1024; // this must be lower than minlargeMessageSize on the SessionFactory

      final int NUMBER_OF_BINDINGS = 100;

      int NUMBER_OF_MESSAGES = 2;

      config.setPagingMaxGlobalSizeBytes(100 * 1024);
      config.setPagingGlobalWatermarkSize(10 * 1024);

      MessagingService messagingService = createService(true, config, new HashMap<String, AddressSettings>());

      messagingService.start();

      try
      {
         ClientSessionFactory sf = createInVMFactory();

         sf.setBlockOnNonPersistentSend(true);
         sf.setBlockOnPersistentSend(true);
         sf.setBlockOnAcknowledge(true);

         ClientSession session = sf.createSession(null, null, false, !transacted, true, false, 0);

         for (int i = 0; i < NUMBER_OF_BINDINGS; i++)
         {
            session.createQueue(ADDRESS, new SimpleString("someQueue" + i), null, true, false);
         }

         ClientProducer producer = session.createProducer(ADDRESS);

         ByteBuffer ioBuffer = ByteBuffer.allocate(MAX_SIZE - 1024); // A single message with almost maxPageSize

         ClientMessage message = null;

         MessagingBuffer bodyLocal = new ByteBufferWrapper(ioBuffer);

         message = session.createClientMessage(true);
         message.setBody(bodyLocal);

         for (int i = 0; i < NUMBER_OF_MESSAGES; i++)
         {
            producer.send(message);

            if (transacted)
            {
               session.commit();
            }
         }

         session.close();

         messagingService.stop();

         messagingService = createService(true, config, new HashMap<String, AddressSettings>());
         messagingService.start();

         sf = createInVMFactory();

         assertTrue(messagingService.getServer().getPostOffice().getPagingManager().getGlobalSize() > 0);

         session = sf.createSession(null, null, false, true, true, false, 0);

         session.start();

         for (int msg = 0; msg < NUMBER_OF_MESSAGES; msg++)
         {

            for (int i = 0; i < NUMBER_OF_BINDINGS; i++)
            {
               ClientConsumer consumer = session.createConsumer(new SimpleString("someQueue" + i));

               ClientMessage message2 = consumer.receive(1000);

               assertNotNull(message2);

               message2.acknowledge();

               assertNotNull(message2);

               consumer.close();

            }
         }

         session.close();

         for (int i = 0; i < NUMBER_OF_BINDINGS; i++)
         {
            Queue queue = (Queue)messagingService.getServer()
                                                 .getPostOffice()
                                                 .getBinding(new SimpleString("someQueue" + i))
                                                 .getBindable();

            assertEquals("Queue someQueue" + i + " was supposed to be empty", 0, queue.getMessageCount());
            assertEquals("Queue someQueue" + i + " was supposed to be empty", 0, queue.getDeliveringCount());
         }

         assertEquals("There are pending messages on the server", 0, messagingService.getServer()
                                                                                     .getPostOffice()
                                                                                     .getPagingManager()
                                                                                     .getGlobalSize());

      }
      finally
      {
         try
         {
            messagingService.stop();
         }
         catch (Throwable ignored)
         {
         }
      }

   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
