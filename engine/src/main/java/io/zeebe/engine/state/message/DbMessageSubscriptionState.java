/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.state.message;

import io.zeebe.db.ColumnFamily;
import io.zeebe.db.TransactionContext;
import io.zeebe.db.ZeebeDb;
import io.zeebe.db.impl.DbCompositeKey;
import io.zeebe.db.impl.DbLong;
import io.zeebe.db.impl.DbNil;
import io.zeebe.db.impl.DbString;
import io.zeebe.engine.state.ZbColumnFamilies;
import io.zeebe.engine.state.mutable.MutableMessageSubscriptionState;
import org.agrona.DirectBuffer;

public final class DbMessageSubscriptionState implements MutableMessageSubscriptionState {

  private final TransactionContext transactionContext;

  // (elementInstanceKey, messageName) => MessageSubscription
  private final DbLong elementInstanceKey;
  private final DbString messageName;
  private final MessageSubscription messageSubscription;
  private final DbCompositeKey<DbLong, DbString> elementKeyAndMessageName;
  private final ColumnFamily<DbCompositeKey<DbLong, DbString>, MessageSubscription>
      subscriptionColumnFamily;

  // (sentTime, elementInstanceKey, messageName) => \0
  private final DbLong sentTime;
  private final DbCompositeKey<DbLong, DbCompositeKey<DbLong, DbString>> sentTimeCompositeKey;
  private final ColumnFamily<DbCompositeKey<DbLong, DbCompositeKey<DbLong, DbString>>, DbNil>
      sentTimeColumnFamily;

  // (messageName, correlationKey, elementInstanceKey) => \0
  private final DbString correlationKey;
  private final DbCompositeKey<DbString, DbString> nameAndCorrelationKey;
  private final DbCompositeKey<DbCompositeKey<DbString, DbString>, DbLong>
      nameCorrelationAndElementInstanceKey;
  private final ColumnFamily<DbCompositeKey<DbCompositeKey<DbString, DbString>, DbLong>, DbNil>
      messageNameAndCorrelationKeyColumnFamily;

  public DbMessageSubscriptionState(
      final ZeebeDb<ZbColumnFamilies> zeebeDb, final TransactionContext transactionContext) {
    this.transactionContext = transactionContext;

    elementInstanceKey = new DbLong();
    messageName = new DbString();
    messageSubscription = new MessageSubscription();
    elementKeyAndMessageName = new DbCompositeKey<>(elementInstanceKey, messageName);
    subscriptionColumnFamily =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.MESSAGE_SUBSCRIPTION_BY_KEY,
            transactionContext,
            elementKeyAndMessageName,
            messageSubscription);

    sentTime = new DbLong();
    sentTimeCompositeKey = new DbCompositeKey<>(sentTime, elementKeyAndMessageName);
    sentTimeColumnFamily =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.MESSAGE_SUBSCRIPTION_BY_SENT_TIME,
            transactionContext,
            sentTimeCompositeKey,
            DbNil.INSTANCE);

    correlationKey = new DbString();
    nameAndCorrelationKey = new DbCompositeKey<>(messageName, correlationKey);
    nameCorrelationAndElementInstanceKey =
        new DbCompositeKey<>(nameAndCorrelationKey, elementInstanceKey);
    messageNameAndCorrelationKeyColumnFamily =
        zeebeDb.createColumnFamily(
            ZbColumnFamilies.MESSAGE_SUBSCRIPTION_BY_NAME_AND_CORRELATION_KEY,
            transactionContext,
            nameCorrelationAndElementInstanceKey,
            DbNil.INSTANCE);
  }

  @Override
  public MessageSubscription get(final long elementInstanceKey, final DirectBuffer messageName) {
    this.messageName.wrapBuffer(messageName);
    this.elementInstanceKey.wrapLong(elementInstanceKey);
    return subscriptionColumnFamily.get(elementKeyAndMessageName);
  }

  @Override
  public void put(final MessageSubscription subscription) {
    elementInstanceKey.wrapLong(subscription.getElementInstanceKey());
    messageName.wrapBuffer(subscription.getMessageName());
    subscriptionColumnFamily.put(elementKeyAndMessageName, subscription);

    correlationKey.wrapBuffer(subscription.getCorrelationKey());
    messageNameAndCorrelationKeyColumnFamily.put(
        nameCorrelationAndElementInstanceKey, DbNil.INSTANCE);
  }

  @Override
  public void visitSubscriptions(
      final DirectBuffer messageName,
      final DirectBuffer correlationKey,
      final MessageSubscriptionVisitor visitor) {

    this.messageName.wrapBuffer(messageName);
    this.correlationKey.wrapBuffer(correlationKey);

    messageNameAndCorrelationKeyColumnFamily.whileEqualPrefix(
        nameAndCorrelationKey,
        (compositeKey, nil) -> {
          return visitMessageSubscription(elementKeyAndMessageName, visitor);
        });
  }

  private Boolean visitMessageSubscription(
      final DbCompositeKey<DbLong, DbString> elementKeyAndMessageName,
      final MessageSubscriptionVisitor visitor) {
    final MessageSubscription messageSubscription =
        subscriptionColumnFamily.get(elementKeyAndMessageName);

    if (messageSubscription == null) {
      throw new IllegalStateException(
          String.format(
              "Expected to find subscription with key %d and %s, but no subscription found",
              elementKeyAndMessageName.getFirst().getValue(),
              elementKeyAndMessageName.getSecond()));
    }
    return visitor.visit(messageSubscription);
  }

  @Override
  public void updateToCorrelatingState(
      final MessageSubscription subscription,
      final DirectBuffer messageVariables,
      final long sentTime,
      final long messageKey) {
    subscription.setMessageVariables(messageVariables);
    subscription.setMessageKey(messageKey);
    updateSentTime(subscription, sentTime);
  }

  @Override
  public void resetSentTime(final MessageSubscription subscription) {
    updateSentTime(subscription, 0);
  }

  @Override
  public void updateSentTimeInTransaction(
      final MessageSubscription subscription, final long sentTime) {
    transactionContext.runInTransaction((() -> updateSentTime(subscription, sentTime)));
  }

  @Override
  public void updateSentTime(final MessageSubscription subscription, final long sentTime) {
    elementInstanceKey.wrapLong(subscription.getElementInstanceKey());
    messageName.wrapBuffer(subscription.getMessageName());

    removeSubscriptionFromSentTimeColumnFamily(subscription);

    subscription.setCommandSentTime(sentTime);
    subscriptionColumnFamily.put(elementKeyAndMessageName, subscription);

    if (sentTime > 0) {
      this.sentTime.wrapLong(subscription.getCommandSentTime());
      sentTimeColumnFamily.put(sentTimeCompositeKey, DbNil.INSTANCE);
    }
  }

  @Override
  public void visitSubscriptionBefore(
      final long deadline, final MessageSubscriptionVisitor visitor) {
    sentTimeColumnFamily.whileTrue(
        (compositeKey, nil) -> {
          final long sentTime = compositeKey.getFirst().getValue();
          if (sentTime < deadline) {
            return visitMessageSubscription(compositeKey.getSecond(), visitor);
          }
          return false;
        });
  }

  @Override
  public boolean existSubscriptionForElementInstance(
      final long elementInstanceKey, final DirectBuffer messageName) {
    this.elementInstanceKey.wrapLong(elementInstanceKey);
    this.messageName.wrapBuffer(messageName);

    return subscriptionColumnFamily.exists(elementKeyAndMessageName);
  }

  @Override
  public boolean remove(final long elementInstanceKey, final DirectBuffer messageName) {
    this.elementInstanceKey.wrapLong(elementInstanceKey);
    this.messageName.wrapBuffer(messageName);

    final MessageSubscription messageSubscription =
        subscriptionColumnFamily.get(elementKeyAndMessageName);

    final boolean found = messageSubscription != null;
    if (found) {
      remove(messageSubscription);
    }
    return found;
  }

  @Override
  public void remove(final MessageSubscription subscription) {
    subscriptionColumnFamily.delete(elementKeyAndMessageName);

    messageName.wrapBuffer(subscription.getMessageName());
    correlationKey.wrapBuffer(subscription.getCorrelationKey());
    messageNameAndCorrelationKeyColumnFamily.delete(nameCorrelationAndElementInstanceKey);

    removeSubscriptionFromSentTimeColumnFamily(subscription);
  }

  private void removeSubscriptionFromSentTimeColumnFamily(final MessageSubscription subscription) {
    if (subscription.getCommandSentTime() > 0) {
      sentTime.wrapLong(subscription.getCommandSentTime());
      sentTimeColumnFamily.delete(sentTimeCompositeKey);
    }
  }
}
