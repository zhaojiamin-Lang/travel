/**
 * Event-driven itinerary repair.
 *
 * <p>Receives closure and delay events, recalculates remaining AMap routes and
 * stores a pending proposal. A new immutable plan version is written only after
 * the proposal is accepted.</p>
 */
package com.travelguide.replan;
