/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.source.mongodb.internal;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCursor;
import io.airbyte.commons.json.Jsons;
import io.airbyte.db.mongodb.MongoUtils;
import io.airbyte.protocol.models.v0.AirbyteMessage;
import io.airbyte.protocol.models.v0.AirbyteMessage.Type;
import io.airbyte.protocol.models.v0.AirbyteRecordMessage;
import io.airbyte.protocol.models.v0.AirbyteStateMessage;
import io.airbyte.protocol.models.v0.AirbyteStateMessage.AirbyteStateType;
import io.airbyte.protocol.models.v0.AirbyteStreamState;
import io.airbyte.protocol.models.v0.CatalogHelpers;
import io.airbyte.protocol.models.v0.ConfiguredAirbyteStream;
import io.airbyte.protocol.models.v0.StreamDescriptor;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A state-emitting iterator that emits a state message every checkpointInterval messages when iterating over a MongoCursor.
 *
 * Will also output a state message as the last message after the wrapper iterator has completed.
 */
class MongoDbStateIterator implements Iterator<AirbyteMessage> {

  private static final Logger LOGGER = LoggerFactory.getLogger(MongoDbStateIterator.class);

  private final MongoCursor<Document> iter;
  private final ConfiguredAirbyteStream stream;
  private final List<String> fields;
  private final Instant emittedAt;
  private final Integer checkpointInterval;

  /**
   * Counts the number of records seen in this batch, resets when a state-message has been generated.
   */
  private int count = 0;

  /**
   * Pointer to the last document _id seen by this iterator, necessary to track for state messages.
   */
  private String lastId = null;

  /**
   * This iterator outputs a final state when the wrapped `iter` has concluded. When this is true, the
   * final message will be returned.
   */
  private boolean finalStateNext = false;

  /**
   * Constructor.
   *
   * @param iter MongoCursor that iterates over Mongo documents
   * @param stream the stream that this iterator represents
   * @param state the initial state of this stream
   * @param emittedAt when this iterator was started
   * @param checkpointInterval how often a state message should be emitted.
   */
  MongoDbStateIterator(final MongoCursor<Document> iter,
      final ConfiguredAirbyteStream stream,
      Optional<MongodbStreamState> state,
      final Instant emittedAt,
      final int checkpointInterval) {
    this.iter = iter;
    this.stream = stream;
    this.checkpointInterval = checkpointInterval;
    this.emittedAt = emittedAt;
    fields = CatalogHelpers.getTopLevelFieldNames(stream).stream().toList();
    lastId = state.map(MongodbStreamState::id).orElse(null);
  }

  @Override
  public boolean hasNext() {
    try {
      if (iter.hasNext()) {
        return true;
      }
    } catch (MongoException e) {
      // If hasNext throws an exception, log it and then treat it as if hasNext returned false.
      LOGGER.info("hasNext threw an exception: {}", e.getMessage(), e);
    }

    if (!finalStateNext) {
      finalStateNext = true;
      return true;
    }

    return false;
  }

  @Override
  public AirbyteMessage next() {
    if ((count > 0 && count % checkpointInterval == 0) || finalStateNext) {
      count = 0;

      final var streamState = new AirbyteStreamState()
          .withStreamDescriptor(new StreamDescriptor()
              .withName(stream.getStream().getName())
              .withNamespace(stream.getStream().getNamespace()));
      if (lastId != null) {
        // TODO add type support in here once more than ObjectId fields are supported
        streamState.withStreamState(Jsons.jsonNode(new MongodbStreamState(lastId)));
      }

      final var stateMessage = new AirbyteStateMessage()
          .withType(AirbyteStateType.STREAM)
          .withStream(streamState);

      return new AirbyteMessage().withType(Type.STATE).withState(stateMessage);
    }

    count++;
    final var document = iter.next();
    final var jsonNode = MongoUtils.toJsonNode(document, fields);

    lastId = document.getObjectId("_id").toString();

    return new AirbyteMessage()
        .withType(Type.RECORD)
        .withRecord(new AirbyteRecordMessage()
            .withStream(stream.getStream().getName())
            .withNamespace(stream.getStream().getNamespace())
            .withEmittedAt(emittedAt.toEpochMilli())
            .withData(jsonNode));
  }

}
