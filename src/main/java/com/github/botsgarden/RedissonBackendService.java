package com.github.botsgarden;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.servicediscovery.Record;
import io.vertx.servicediscovery.spi.ServiceDiscoveryBackend;

import org.redisson.api.listener.MessageListener;
import org.redisson.config.Config;
import org.redisson.api.*;
import org.redisson.Redisson;


import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * An implementation of the discovery backend based on Redis.
 * Completely inspired by the version of Clément Escoffier
 * https://github.com/vert-x3/vertx-service-discovery/blob/master/vertx-service-discovery-backend-redis/src/main/java/io/vertx/servicediscovery/backend/redis/RedisBackendService.java
 *
 * @author <a href="https://twitter.com/k33g_org">Philippe Charrière</a>
 */

public class RedissonBackendService implements ServiceDiscoveryBackend {

  private RedissonClient redisson;

  private String key;
  private String channel;

  private RTopic<JsonObject> topic;
  private RSetAsync<Record> rSet;

  private static JsonObject configuration;

  private static RTopic<JsonObject> staticTopic;
  private static RedissonClient staticRedisson;

  /*
  private static RTopic<JsonObject> topic() {
    return  staticTopic;
  }
  */

  private static void setTopic() {
    //String key = configuration.getString("key", "records");
    String channel = configuration.getString("channel", "default");
    try {
      Config config = new Config();
      config.useSingleServer().setAddress(
        configuration.getString("redis_url","redis://127.0.0.1:6379" )
      ).setPassword(
        configuration.getString("redis_password", null)
      );
      staticRedisson = Redisson.create(config);
      staticTopic = staticRedisson.getTopic(channel);
    } catch (Exception e) {
      throw new Error("😡 : " + e.getMessage());
    }
  }

  public static void onEvent(Handler<AsyncResult<JsonObject>> handler) {
    staticTopic.addListener(new MessageListener<JsonObject>() {
      @Override
      public void onMessage(String channel, JsonObject msg) {
        handler.handle(Future.succeededFuture(msg));
      }
    });
  }

  @Override
  public void init(Vertx vertx, JsonObject configuration) {
    System.out.println("🤖 Redisson backend initializing...");

    key = configuration.getString("key", "records");
    channel = configuration.getString("channel", "default");

    try {
      Config config = new Config();
      config.useSingleServer().setAddress(
        configuration.getString("redis_url","redis://127.0.0.1:6379" )
      )
      .setPassword(
        configuration.getString("redis_password", null)
      );
      redisson = Redisson.create(config);
      RedissonBackendService.configuration = configuration; // usable for topic
      RedissonBackendService.setTopic();


    } catch (Exception e) {
      throw new Error("😡 : " + e.getMessage());
    }
    topic = redisson.getTopic(channel);
    rSet = redisson.getSet(key);
  }


  @Override
  public void store(Record record, Handler<AsyncResult<Record>> resultHandler) {
    if (record.getRegistration() != null) {
      resultHandler.handle(Future.failedFuture("The record has already been registered"));
      return;
    }
    String uuid = UUID.randomUUID().toString();
    record.setRegistration(uuid);

    rSet.addAsync(record).handle((res, err) -> {
      if(res) {
        resultHandler.handle(Future.succeededFuture(record));
        try {
          topic.publish(
            new JsonObject()
              .put("action", "store")
              .put("record", record.toJson())
          );
        } catch (Exception e) {
          e.printStackTrace();
        }
      } else {
        resultHandler.handle(Future.failedFuture(err.getCause()));
        topic.publish(
          new JsonObject()
            .put("action", "error")
            .put("error", err.getMessage())
            .put("when", "store")
        );
      }
      return res;
    });
  }

  @Override
  public void remove(Record record, Handler<AsyncResult<Record>> resultHandler) {
    Objects.requireNonNull(record.getRegistration(), "No registration id in the record");
    remove(record.getRegistration(), resultHandler);
  }

  @Override
  public void remove(String uuid, Handler<AsyncResult<Record>> resultHandler) {
    Objects.requireNonNull(uuid, "No registration id in the record");
    rSet.deleteAsync().handle((res, err) -> {
      if(res) {
        Record deletedRecord = new Record(new JsonObject().put("registration",uuid));

        resultHandler.handle(Future.succeededFuture(
          deletedRecord
        ));
        try {
          topic.publish(
            new JsonObject()
              .put("action", "remove")
              .put("record", deletedRecord.toJson())
          );
        } catch (Exception e) {
          e.printStackTrace();
        }
      } else {
        resultHandler.handle(Future.failedFuture(err.getCause()));
        topic.publish(
          new JsonObject()
            .put("action", "error")
            .put("error", err.getMessage())
            .put("when", "remove")
        );
      }
      return res;
    });


  }

  @Override
  public void update(Record record, Handler<AsyncResult<Void>> resultHandler) {
    Objects.requireNonNull(record.getRegistration(), "No registration id in the record");
    rSet.addAsync(record).handle((res, err) -> {
      if(res) {
        resultHandler.handle(Future.succeededFuture());
        try {
          topic.publish(
            new JsonObject()
              .put("action", "update")
              .put("record", record.toJson())
          );
        } catch (Exception e) {
          e.printStackTrace();
        }
      } else {
        resultHandler.handle(Future.failedFuture(err.getCause()));
        topic.publish(
          new JsonObject()
            .put("action", "error")
            .put("error", err.getMessage())
            .put("when", "update")
        );
      }
      return res;
    });
  }

  @Override
  public void getRecords(Handler<AsyncResult<List<Record>>> resultHandler) {

    rSet.readAllAsync().handle((res, err) -> {
      if (err != null) {
        resultHandler.handle(Future.failedFuture(err.getCause()));
      } else {
        List<Record> records = res.stream().map(item -> new Record(JsonObject.mapFrom(item)))
          .collect(Collectors.toList());
        resultHandler.handle(Future.succeededFuture(
          records
        ));
      }
      return res;
    });
  }

  @Override
  public void getRecord(String uuid, Handler<AsyncResult<Record>> resultHandler) {
    //TODO
  }
}
