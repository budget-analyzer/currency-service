package org.budgetanalyzer.currency.domain.event;

import org.budgetanalyzer.currency.domain.CurrencySeries;
import org.budgetanalyzer.currency.service.CurrencyService;

/**
 * Domain event published when a new enabled currency series is successfully created.
 *
 * <p>This event is published within the same database transaction as the currency series creation.
 * Spring Modulith automatically stores this event in the event_publication table for guaranteed
 * delivery via the transactional outbox pattern.
 *
 * <p><b>Event Flow:</b>
 *
 * <ol>
 *   <li>CurrencyService creates enabled currency entity and publishes this event
 *   <li>Spring Modulith persists event to event_publication table (same transaction)
 *   <li>Database transaction commits (currency + event both saved atomically)
 *   <li>Spring Modulith asynchronously processes event via @ApplicationModuleListener
 *   <li>Event listener publishes external message to RabbitMQ to trigger exchange rate import
 *   <li>Event marked as completed in event_publication table
 * </ol>
 *
 * <p><b>Transactional Outbox Pattern Benefits:</b>
 *
 * <ul>
 *   <li><b>100% guaranteed delivery</b> - Event survives application crashes since it's persisted
 *       in the database alongside the currency entity in the same transaction
 *   <li><b>Exactly-once semantics</b> - Events stored atomically with business data, no dual-write
 *       problem
 *   <li><b>Async processing</b> - HTTP requests don't wait for message publishing to RabbitMQ
 *   <li><b>Automatic retries</b> - Spring Modulith retries failed event processing until success
 *   <li><b>Event replay capability</b> - Failed events can be manually replayed from database
 * </ul>
 *
 * <p><b>Messaging Decision Logic:</b>
 *
 * <p>{@link CurrencyService} publishes this event only for enabled currencies. This avoids
 * persisting and processing outbox events for disabled currencies that cannot produce import
 * requests.
 *
 * <p><b>Architecture:</b>
 *
 * <pre>
 * HTTP Request (Thread 1)
 * │
 * ├─> CurrencyService.create()
 * │   ├─> repository.save(currency)           [Transaction starts]
 * │   ├─> if enabled: eventPublisher.publishEvent(...)
 * │   │   └─> Spring Modulith intercepts
 * │   │       └─> Saves to event_publication  [Same transaction!]
 * │   └─> return                               [Transaction commits]
 * │
 * └─> HTTP 201 Created (request completes)
 *
 * Background (Thread 2)
 * │
 * └─> Spring Modulith polls event_publication
 *     ├─> Finds unpublished events
 *     ├─> Calls @ApplicationModuleListener
 *     │   └─> Publish exchange rate import request
 *     └─> Marks event as published
 * </pre>
 *
 * @param currencySeriesId The unique identifier of the newly created currency series
 * @param currencyCode The ISO 4217 currency code (e.g., "USD", "EUR", "JPY")
 * @param enabled Whether the currency is enabled for exchange rate access
 * @param correlationId The correlation ID from the originating HTTP request for distributed tracing
 *     across async boundaries. This allows tracing the entire flow from HTTP request through async
 *     event processing to external message publishing.
 * @see CurrencyService#create(CurrencySeries)
 * @see org.budgetanalyzer.currency.messaging.listener
 *     .MessagingEventListener#onCurrencyCreated(CurrencyCreatedEvent)
 */
public record CurrencyCreatedEvent(
    Long currencySeriesId, String currencyCode, boolean enabled, String correlationId) {}
