package com.benesquivelmusic.daw.sdk.event;

/**
 * Sealed root of every event that may travel the typed {@link EventBus}.
 *
 * <p>The bus carries two sibling families:</p>
 * <ul>
 *   <li>{@link DawEvent} — domain facts the engine produces (transport, mixer,
 *       track, clip, project, automation, plugin, x-run). These cross the
 *       core↔UI boundary and are consumed by both engine-side and UI-side
 *       subscribers.</li>
 *   <li>{@link UiEvent} — UI-only discrete facts (selection changed, undo-state
 *       changed) that <em>never</em> need to reach the core (Control
 *       Synchronization Design Book §4.2, §7, Appendix A; story 292).</li>
 * </ul>
 *
 * <p>{@code BusEvent} exists so a single {@link EventBus} can carry both families
 * without {@link UiEvent} having to be a {@link DawEvent} — the design book
 * keeps the {@code DawEvent} {@code permits} clause closed to the engine domain
 * (story 292 Non-Goal: "Do not add the new UI events to {@code DawEvent}'s
 * {@code permits}"), and the bus binds to this shared super-type instead of to
 * {@code DawEvent}, so one bus carries UI facts too rather than spinning up a
 * second bus (story 292: "Prefer reusing the existing bus infrastructure over a
 * third bus").</p>
 *
 * <p>The hierarchy is sealed: the two permitted families are the only things a
 * bus can carry, so a third family cannot be introduced without an explicit
 * edit here. There is intentionally no exhaustive {@code switch} over
 * {@code BusEvent} anywhere — consumers always subscribe to a concrete
 * {@code DawEvent}/{@code UiEvent} sub-type; this type is purely the bus's
 * type bound.</p>
 *
 * @see DawEvent
 * @see UiEvent
 * @see EventBus
 */
public sealed interface BusEvent permits DawEvent, UiEvent {
}
