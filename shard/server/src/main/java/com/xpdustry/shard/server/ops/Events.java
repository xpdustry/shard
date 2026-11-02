package com.xpdustry.shard.server.ops;

import tools.jackson.databind.*;
import tools.jackson.databind.json.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * The event stream behind {@code GET /api/v1/events} (Server-Sent Events). Publishers hand in an event type and a
 * JSON-serializable payload; each subscriber has its own bounded queue. A subscriber that falls behind by more than
 * its queue is dropped rather than slowing anyone down; it reconnects with {@code Last-Event-ID} and replays from
 * the last {@link #RETAINED} events, or learns that it missed some.
 */
public final class Events{
    public static final int RETAINED = 256;
    public static final int QUEUE = 256;
    static final ObjectMapper JSON = JsonMapper.builder().build();

    private final ArrayDeque<Event> retained = new ArrayDeque<>(RETAINED);
    private final Set<Subscription> subscribers = ConcurrentHashMap.newKeySet();
    private long next = 1;

    /**
     * One event.
     *
     * @param id grows by one per event, from 1
     * @param data the payload as one line of JSON
     */
    public record Event(long id, String type, String data){
    }

    /** Publishes an event to every subscriber; returns its id. */
    public long publish(String type, Object payload){
        if(!type.matches("[a-z][a-z0-9.-]*")) throw new IllegalArgumentException("bad event type " + type);
        String data = JSON.writeValueAsString(payload);
        Event event;
        synchronized(this){
            event = new Event(next++, type, data);
            if(retained.size() == RETAINED) retained.removeFirst();
            retained.addLast(event);
            for(var subscription : subscribers) subscription.offer(event);
        }
        return event.id;
    }

    /**
     * Subscribes. With {@code lastEventId} above 0 the subscription first replays every retained event after it;
     * {@link Subscription#missed()} says whether some were already gone.
     */
    public Subscription subscribe(long lastEventId){
        synchronized(this){
            var subscription = new Subscription(this);
            if(lastEventId > 0){
                long oldest = retained.isEmpty() ? next : retained.getFirst().id;
                // an id at or past ours is from before a restart: everything since is unknown to the client
                boolean stale = lastEventId >= next;
                subscription.missed = stale || lastEventId + 1 < oldest;
                for(var event : retained) if(stale || event.id > lastEventId) subscription.offer(event);
            }
            subscribers.add(subscription);
            return subscription;
        }
    }

    public int subscribers(){
        return subscribers.size();
    }

    /** One subscriber's queue. */
    public static final class Subscription implements AutoCloseable{
        private final Events events;
        private final ArrayBlockingQueue<Event> queue = new ArrayBlockingQueue<>(QUEUE);
        private volatile boolean overflowed, closed;
        private boolean missed;

        Subscription(Events events){
            this.events = events;
        }

        void offer(Event event){
            if(!queue.offer(event)) overflowed = true;
        }

        /** The next event, or null after {@code timeout} or once closed. */
        public Event poll(long timeout, TimeUnit unit) throws InterruptedException{
            if(closed || overflowed) return null;
            return queue.poll(timeout, unit);
        }

        /** Whether events were lost since the {@code Last-Event-ID} it subscribed with. */
        public boolean missed(){
            return missed;
        }

        /** Whether it fell behind and was cut off; the stream should end. */
        public boolean overflowed(){
            return overflowed;
        }

        public boolean isClosed(){
            return closed;
        }

        @Override
        public void close(){
            closed = true;
            events.subscribers.remove(this);
        }
    }
}
