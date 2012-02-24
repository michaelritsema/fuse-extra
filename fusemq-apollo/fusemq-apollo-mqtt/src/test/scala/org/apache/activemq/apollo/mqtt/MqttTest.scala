/*
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.activemq.apollo.mqtt

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterEach
import java.lang.String
import org.fusesource.hawtdispatch._
import org.fusesource.hawtbuf.Buffer._
import java.net.InetSocketAddress
import org.apache.activemq.apollo.broker._
import org.apache.activemq.apollo.util._
import org.fusesource.mqtt.client._
import QoS._
import org.apache.activemq.apollo.dto.TopicStatusDTO
import java.util.concurrent.TimeUnit._
import org.fusesource.stomp.codec.StompFrame
import org.fusesource.stomp.client.{Constants, Stomp}
import org.fusesource.hawtbuf.{Buffer, UTF8Buffer}
import FileSupport._

class MqttTestSupport extends FunSuiteSupport with ShouldMatchers with BeforeAndAfterEach with Logging {
  var broker: Broker = null
  var port = 0

  val broker_config_uri = "xml:classpath:apollo-mqtt.xml"

  override protected def beforeAll() = {
    super.beforeAll()
    test_data_dir.recursive_delete
    try {
      broker_start
    } catch {
      case e:Throwable => e.printStackTrace
    }
  }

  override protected def afterAll() = {
    broker_stop
  }

  def broker_start {
    info("Loading broker configuration from the classpath with URI: " + broker_config_uri)
    broker = BrokerFactory.createBroker(broker_config_uri)
    ServiceControl.start(broker, "Starting broker")
    port = broker.get_socket_address.asInstanceOf[InetSocketAddress].getPort
  }

  def broker_stop {
    if( broker!=null ) {
      ServiceControl.stop(broker, "Stopping broker")
      broker = null
    }
  }

  def broker_restart = {
    broker_stop
    broker_start
  }

  var clients = List[MqttClient]()
  var client = create_client

  def create_client = {
    val client = new MqttClient
    clients ::= client
    client
  }

  override protected def afterEach() = {
    super.afterEach
    clients.foreach(_.disconnect)
    clients = Nil
    client = create_client
  }

  def queue_exists(name:String):Boolean = {
    val host = broker.virtual_hosts.get(ascii("default")).get
    host.dispatch_queue.future {
      val router = host.router.asInstanceOf[LocalRouter]
      router.local_queue_domain.destination_by_id.get(name).isDefined
    }.await()
  }

  def topic_exists(name:String):Boolean = {
    val host = broker.virtual_hosts.get(ascii("default")).get
    host.dispatch_queue.future {
      val router = host.router.asInstanceOf[LocalRouter]
      router.local_topic_domain.destination_by_id.get(name).isDefined
    }.await()
  }

  def topic_status(name:String):TopicStatusDTO = {
    val host = broker.virtual_hosts.get(ascii("default")).get
    sync(host) {
      val router = host.router.asInstanceOf[LocalRouter]
      router.local_topic_domain.destination_by_id.get(name).get.status
    }
  }
  
  class MqttClient extends MQTT {

    var connection: BlockingConnection = _

    def open(host: String, port: Int) = {
      setHost(host, port)
      connection = blockingConnection();
      connection.connect();
    }
  
    def disconnect() = {
      connection.disconnect()
    }
  }

  def connect(c:MqttClient=client) = {
    c.open("localhost", port)
  }
  def disconnect(c:MqttClient=client) = {
    c.disconnect()
  }
  def kill(c:MqttClient=client) = {
    c.connection.kill()
  }

  def publish(topic:String, message:String, qos:QoS=AT_MOST_ONCE, retain:Boolean=false, c:MqttClient=client) = {
    c.connection.publish(topic, message.getBytes("UTF-8"), qos, retain)
  }
  def subscribe(topic:String, qos:QoS=AT_MOST_ONCE, c:MqttClient=client) = {
    c.connection.subscribe(Array(new org.fusesource.mqtt.client.Topic(topic, qos)))
  }
  def unsubscribe(topic:String, c:MqttClient=client) = {
    c.connection.unsubscribe(Array(topic))
  }

  def should_receive(body:String, topic:String=null, c:MqttClient=client) = {
    val msg = c.connection.receive(5, SECONDS);
    expect(true)(msg!=null)
    if(topic!=null) {
      msg.getTopic should equal(topic)
    }
    new String(msg.getPayload, "UTF-8") should equal(body)
    msg.ack()
  }

}

class MqttCleanSessionTest extends MqttTestSupport {

  test("Subscribing to overlapping topics") {
    connect()
    subscribe("overlap/#")
    subscribe("overlap/a/b")
    subscribe("overlap/a/+")

    // This is checking that we don't get duplicate messages
    // due to the overlapping nature of the subscriptions.
    publish("overlap/a/b", "1", EXACTLY_ONCE)
    should_receive("1", "overlap/a/b")
    publish("overlap/a", "2", EXACTLY_ONCE)
    should_receive("2", "overlap/a")
    publish("overlap/a/b", "3", EXACTLY_ONCE)
    should_receive("3", "overlap/a/b")

    // Dropping subscriptions should not affect us while there
    // is still a matching sub left.
    unsubscribe("overlap/#")
    publish("overlap/a/b", "4", EXACTLY_ONCE)
    should_receive("4", "overlap/a/b")

    unsubscribe("overlap/a/b")
    publish("overlap/a/b", "5", EXACTLY_ONCE)
    should_receive("5", "overlap/a/b")

    // Drop the last subscription.. but setup root sub we can test
    // without using timeouts.
    publish("foo", "6", EXACTLY_ONCE) // never did match
    unsubscribe("overlap/a/+")
    publish("overlap/a/b", "7", EXACTLY_ONCE) // should not match anymore.

    // Send a message through to flush everything out and verify none of the other
    // are getting routed to us.
    println("subscribing...")
    subscribe("#")
    println("publishinng...")
    publish("foo", "8", EXACTLY_ONCE)
    println("receiving...")
    should_receive("8", "foo")

  }

  def will_test(kill_action: (MqttClient)=>Unit) = {
    connect()
    subscribe("will/foo")

    val will_client = new MqttClient
    will_client.setWillTopic("will/foo")
    will_client.setWillQos(AT_LEAST_ONCE)
    will_client.setWillRetain(false)
    will_client.setWillMessage("1");
    kill_action(will_client)
    should_receive("1", "will/foo")
  }

  test("Will sent on socket failure") {
    will_test{ client=>
      connect(client)
      kill(client)
    }
  }

  test("Will sent on keepalive failure") {
    will_test{ client=>
      val queue = createQueue("")
      client.setKeepAlive(1)
      client.setDispatchQueue(queue)
      client.setReconnectAttemptsMax(0)
      client.setDispatchQueue(queue);
      connect(client)

      // Client should time out once we suspend the queue.
      queue.suspend()
      Thread.sleep(1000*2);
      queue.resume()
    }
  }

  test("Will NOT sent on clean disconnect") {
    expect(true) {
      try {
        will_test{ client=>
          connect(client)
          disconnect(client)
        }
        false
      } catch {
        case e:Throwable =>
          e.printStackTrace()
          true
      }
    }
  }

  test("Publish") {
    connect()
    publish("test", "message", EXACTLY_ONCE)
    topic_status("test").metrics.enqueue_item_counter should be(1)

    publish("test", "message", AT_LEAST_ONCE)
    topic_status("test").metrics.enqueue_item_counter should be(2)

    publish("test", "message", AT_MOST_ONCE)

    within(1, SECONDS) { // since AT_MOST_ONCE use non-blocking sends.
      topic_status("test").metrics.enqueue_item_counter should be(3)
    }
  }

  test("Subscribe") {
    connect()
    subscribe("foo")
    publish("foo", "1", EXACTLY_ONCE)
    should_receive("1", "foo")
  }

  test("Subscribing wiht multi-level wildcard") {
    connect()
    subscribe("mwild/#")
    publish("mwild", "1", EXACTLY_ONCE)
    // Should not match
    publish("mwild.", "2", EXACTLY_ONCE)
    publish("mwild/hello", "3", EXACTLY_ONCE)
    publish("mwild/hello/world", "4", EXACTLY_ONCE)

    for( i <- List(("mwild", "1"), ("mwild/hello","3"), ("mwild/hello/world","4")) ) {
      should_receive(i._2, i._1)
    }
  }

  test("Subscribing with single-level wildcard") {
    connect()
    subscribe("swild/+")
    // Should not a match
    publish("swild", "1", EXACTLY_ONCE)
    publish("swild/hello", "2", EXACTLY_ONCE)
    // Should not match..
    publish("swild/hello/world", "3", EXACTLY_ONCE)
    // Should match. so.cool is only one level, but STOMP would treat it like 2,
    // Lets make sure Apollo's STOMP support does not mess with us.
    publish("swild/so.cool", "4", EXACTLY_ONCE)

    for( i <- List(("swild/hello", "2"), ("swild/so.cool","4")) ) {
      should_receive(i._2, i._1)
    }
  }

  test("Retained Messages are retained") {
    connect()
    publish("retained", "1", AT_LEAST_ONCE, false)
    publish("retained", "2", AT_LEAST_ONCE, true)
    publish("retained", "3", AT_LEAST_ONCE, false)
    subscribe("retained")
    should_receive("2", "retained")
  }

  test("Non-retained Messages are not retained") {
    connect()
    publish("notretained", "1", AT_LEAST_ONCE, false)
    subscribe("notretained")
    publish("notretained", "2", AT_LEAST_ONCE, false)
    should_receive("2", "notretained")
  }

  test("You can clear out topic's retained message, by sending a retained empty message.") {
    connect()
    publish("clearretained", "1", AT_LEAST_ONCE, true)
    publish("clearretained", "", AT_LEAST_ONCE, true)
    subscribe("clearretained")
    publish("clearretained", "2", AT_LEAST_ONCE, false)
    should_receive("2", "clearretained")
  }

}

class MqttExistingSessionTest extends MqttTestSupport {
  client.setCleanSession(false);
  client.setClientId("default")

  def restart = {}

  test("Subscribe is remembered on existing sessions.") {
    connect()
    subscribe("existing/sub")

    // reconnect...
    disconnect()
    restart
    connect()

    // The subscribe should still be remembered.
    publish("existing/sub", "1", EXACTLY_ONCE)
    should_receive("1", "existing/sub")
  }
}

class MqttExistingSessionOnLevelDBTest extends MqttExistingSessionTest {
  override val broker_config_uri = "xml:classpath:apollo-mqtt-leveldb.xml"
  override def restart = broker_restart
}

class MqttExistingSessionOnBDBTest extends MqttExistingSessionTest {
  override val broker_config_uri = "xml:classpath:apollo-mqtt-bdb.xml"
  override def restart = broker_restart
}

class MqttConnectionTest extends MqttTestSupport {

  test("MQTT CONNECT") {
    client.open("localhost", port)
  }

  test("MQTT Broker times out idle connection") {

    val queue = createQueue("test")

    client.setKeepAlive(1)
    client.setDispatchQueue(queue)
    client.setReconnectAttemptsMax(0)
    client.setDispatchQueue(queue);
    client.open("localhost", port)

    client.connection.isConnected should be(true)
    queue.suspend() // this will cause the client to hang
    Thread.sleep(1000*2);
    queue.resume()
    within(1, SECONDS) {
      client.connection.isConnected should be(false)
    }
  }

}

class MqttQosTest extends MqttTestSupport {

  //
  // Lets make sure we can publish and subscribe with all the QoS combinations.
  //
  for(clean <- List(true, false)) {
    for(send_qos <- List(AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE)) {
      for(receive_qos <- List(AT_MOST_ONCE, AT_LEAST_ONCE, EXACTLY_ONCE)) {
        test("Publish "+send_qos+" and subscribe "+receive_qos+" on clean session: "+clean) {
          val topic = "qos/"+send_qos+"/"+receive_qos+"/"+clean
          client.setClientId(topic)
          client.setCleanSession(clean)
          connect()
          subscribe(topic, receive_qos)
          publish(topic, "1", send_qos)
          should_receive("1", topic)
        }
      }
    }
  }
}


class MqttStompInteropTest extends MqttTestSupport {
  import Constants._
  import Buffer._

  test("MQTT to STOMP via topic") {
    val stomp = new Stomp("localhost", port).connectFuture().await()

    // Setup the STOMP subscription.
    val subscribe = new StompFrame(SUBSCRIBE)
    subscribe.addHeader(ID, ascii("0"))
    subscribe.addHeader(DESTINATION, ascii("/topic/mqtt.to.stomp"))
    stomp.request(subscribe).await()

    // Send from MQTT.
    connect()
    publish("mqtt/to/stomp", "Hello World", AT_LEAST_ONCE)

    val frame = stomp.receive().await(5, SECONDS)
    expect(true, "receive timeout")(frame!=null)
    frame.action().toString should be(MESSAGE.toString)
    frame.contentAsString() should be("Hello World")

  }

  test("STOMP to MQTT via topic") {
    connect()
    subscribe("stomp/to/mqtt")

    val stomp = new Stomp("localhost", port).connectFuture().await()
    val send = new StompFrame(SEND)
    send.addHeader(DESTINATION, ascii("/topic/stomp.to.mqtt"))
    send.addHeader(MESSAGE_ID, ascii("test"))
    send.content(ascii("Hello World"))
    stomp.send(send)

    should_receive(
      "MESSAGE\n" +
      "content-length:11\n" +
      "message-id:test\n" +
      "destination:/topic/stomp.to.mqtt\n" +
      "\n" +
      "Hello World",
      "stomp/to/mqtt")
  }
}