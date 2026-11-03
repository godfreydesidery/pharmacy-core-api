package tz.co.pharmacy.modules.notifications.domain;

/** A message's place in the outbox. */
public enum MessageStatus {
  /** Waiting for its first attempt. */
  QUEUED,
  /** Claimed by a sender; due again when its lease ({@code nextAttemptAt}) passes. */
  SENDING,
  SENT,
  /** The last attempt failed; retried at {@code nextAttemptAt} until attempts run out. */
  FAILED
}
