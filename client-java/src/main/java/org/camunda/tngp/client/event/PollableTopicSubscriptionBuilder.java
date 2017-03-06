package org.camunda.tngp.client.event;

/**
 * <p>Builder used to subscribed to all events of any kind of topic. Builds a <code>pollable</code> subscription,
 * i.e. where the method {@link PollableTopicSubscription#poll(TopicEventHandler)} must be invoked
 * to trigger event handling.
 *
 * <p>By default, a subscription starts at the current tail of the topic (see {@link #startAtTailOfTopic()}).
 */
public interface PollableTopicSubscriptionBuilder
{

    /**
     * Defines the position at which to start receiving events from.
     * A <code>position</code> greater than the current tail position
     * of the topic is equivalent to starting at the tail position. In this case,
     * events with a lower position than the supplied position may be received.
     *
     * @param position the position in the topic at which to start receiving events from
     * @return this builder
     */
    PollableTopicSubscriptionBuilder startAtPosition(long position);

    /**
     * Same as invoking {@link #startAtPosition(long)} with the topic's current tail position.
     * In particular, it is guaranteed that this subscription does not receive any event that
     * was receivable before this subscription is opened.
     *
     * @return this builder
     */
    PollableTopicSubscriptionBuilder startAtTailOfTopic();

    /**
     * Same as invoking {@link #startAtPosition(long)} with <code>position = 0</code>.
     *
     * @return this builder
     */
    PollableTopicSubscriptionBuilder startAtHeadOfTopic();

    /**
     * Opens a new topic subscription with the defined parameters.
     *
     * @return a new subscription
     */
    PollableTopicSubscription open();
}