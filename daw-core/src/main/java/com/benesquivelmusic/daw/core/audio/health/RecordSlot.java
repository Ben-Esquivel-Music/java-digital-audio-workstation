package com.benesquivelmusic.daw.core.audio.health;

/**
 * One preallocated descriptor slot of an {@link AudioHealthLane}'s ring
 * (story 337, rulings E21 and E22). Plain fields: the lane's owner writes
 * them and then publishes the slot with a release store of the ring's tail;
 * the drain reads them after an acquiring load of the tail, copies them, and
 * nulls the two references before releasing the slot through the head.
 */
final class RecordSlot {

    int category;
    Throwable failure;
    Object subject;
    int detail;
    long stamp;
}
