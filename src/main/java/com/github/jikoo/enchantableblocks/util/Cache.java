package com.github.jikoo.enchantableblocks.util;

import com.google.common.collect.TreeMultimap;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Range;
import org.jetbrains.annotations.TestOnly;

/**
 * A minimal thread-safe time-based cache implementation backed by a HashMap and TreeMultimap.
 *
 * @param <K> the type of key
 * @param <V> the type of value
 */
public class Cache<K, V> {

  /**
   * A builder for a Cache.
   *
   * @param <K> the type of key
   * @param <V> the type of value
   */
  public static class CacheBuilder<K, V> {
    private @NotNull Clock clock = Clock.systemUTC();
    private long retention = 300_000L;
    private long lazyFrequency = 10_000L;
    private @Nullable BiFunction<K, Boolean, V> load;
    private @Nullable BiPredicate<K, V> inUseCheck;
    private @Nullable BiConsumer<K, V> postRemoval;

    /**
     * Set the {@link Clock} used by the {@link Cache}.
     *
     * @param clock the {@code Clock}
     * @return the modified builder
     */
    @TestOnly
    CacheBuilder<K, V> withClock(@NotNull Clock clock) {
      this.clock = clock;
      return this;
    }

    /**
     * Set the loading function used by the {@link Cache} to provide new values.
     *
     * @param load the loading function
     * @return the modified builder
     */
    public CacheBuilder<K, V> withLoadFunction(final @Nullable BiFunction<K, Boolean, V> load) {
      this.load = load;
      return this;
    }

    /**
     * Set the in use function used by the {@link Cache} to see if values should not expire.
     *
     * @param inUse the in use function
     * @return the modified builder
     */
    public CacheBuilder<K, V> withInUseCheck(final @Nullable BiPredicate<K, V> inUse) {
      this.inUseCheck = inUse;
      return this;
    }

    /**
     * Set the post-removal consumer used by the {@link Cache} after values are removed.
     *
     * @param postRemove the post-removal consumer
     * @return the modified builder
     */
    public CacheBuilder<K, V> withPostRemoval(final @Nullable BiConsumer<K, V> postRemove) {
      this.postRemoval = postRemove;
      return this;
    }

    /**
     * Set the retention duration used by the {@link Cache}.
     *
     * @param retention the retention duration
     * @return the modified builder
     */
    public CacheBuilder<K, V> withRetention(
        @Range(from = 60_000, to = Long.MAX_VALUE) final long retention) {
      this.retention = Math.max(60_000, retention);
      return this;
    }

    /**
     * Set the lazy check frequency used by the {@link Cache}.
     *
     * @param lazyFrequency the lazy check frequency
     * @return the modified builder
     */
    public CacheBuilder<K, V> withLazyFrequency(
        @Range(from = 0, to = Long.MAX_VALUE) final long lazyFrequency) {
      this.lazyFrequency = Math.max(0, lazyFrequency);
      return this;
    }

    /**
     * Construct a {@link Cache} with the given setting.
     *
     * @return the {@code Cache}
     */
    public Cache<K, V> build() {
      return new Cache<>(
          this.clock,
          this.retention,
          this.lazyFrequency,
          this.load, this.inUseCheck,
          this.postRemoval);
    }
  }

  private final @NotNull Clock clock;
  private final @NotNull Map<K, V> internal;
  private final @NotNull TreeMultimap<Long, K> expiry;
  private final long retention;
  private final long lazyFrequency;
  private final @NotNull AtomicLong lastLazyCheck;
  private final @Nullable BiFunction<K, Boolean, V> load;
  private final @Nullable BiPredicate<K, V> inUseCheck;
  private final @Nullable BiConsumer<K, V> postRemoval;

  /**
   * Constructs a Cache with the specified retention duration, in use function, and post-removal
   * function.
   *
   * @param retention duration after which keys are automatically invalidated if not in use
   * @param inUseCheck Function used to check if a key is considered in use
   * @param postRemoval Function used to perform any operations required when a key is invalidated
   */
  private Cache(final @NotNull Clock clock, final long retention, long lazyFrequency,
      final @Nullable BiFunction<K, Boolean, V> load, final @Nullable BiPredicate<K, V> inUseCheck,
      final @Nullable BiConsumer<K, V> postRemoval) {
    this.internal = new HashMap<>();
    this.clock = clock;

    this.expiry = TreeMultimap.create(
        Comparator.naturalOrder(), (k1, k2) -> k1 == k2 || k1.equals(k2) ? 0 : 1);

    this.load = load;
    this.retention = retention;
    this.lazyFrequency = lazyFrequency;
    this.lastLazyCheck = new AtomicLong(0);
    this.inUseCheck = inUseCheck;
    this.postRemoval = postRemoval;
  }

  /**
   * Set a key and value pair. Keys are unique. Using an existing key will cause the old value to
   * be overwritten and the expiration timer to be reset.
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   */
  public void put(final @NotNull K key, final @Nullable V value) {
    // Run lazy check to clean cache
    this.lazyCheck();

    synchronized (this.internal) {
      this.internal.put(key, value);
      this.expiry.put(clock.millis() + this.retention, key);
    }
  }

  /**
   * Gets the value for a specific key.
   *
   * <p>If a load function is defined and the key is not present, the load function will be used to
   * create a new value. The load function may return null values.
   *
   * @param key the key whose associated value is to be returned
   * @return the value to which the specified key is mapped or null if no value is mapped for the
   *     key and no load function is defined
   */
  public @Nullable V get(final @NotNull K key) {
    return this.get(key, true);
  }

  /**
   * Gets the value for a specific key.
   *
   * <p>If a load function is defined and the key is not present, the load function will be used to
   * create a new value if requested. The load function may return null values.
   *
   * <p>N.B. If a load function is provided, it will always be used to attempt to load existing
   * values.
   *
   *
   * @param key the key whose associated value is to be returned
   * @param create whether the load function should create a new value if none exists to be loaded
   * @return the value to which the specified key is mapped or null
   */
  public @Nullable V get(final @NotNull K key, final boolean create) {
    // Run lazy check to clean cache
    this.lazyCheck();

    synchronized (this.internal) {
      V value;
      if (!this.internal.containsKey(key) && this.load != null) {
        value = this.load.apply(key, create);
        if (value != null) {
          this.internal.put(key, value);
        }
      } else {
        value = this.internal.get(key);
      }

      if (value != null) {
        this.expiry.put(clock.millis() + this.retention, key);
      }

      return value;
    }
  }

  /**
   * Returns true if the specified key is mapped to a value.
   *
   * @param key key to check if a mapping exists for
   * @return true if a mapping exists for the specified key
   */
  public boolean containsKey(final @NotNull K key) {
    // Run lazy check to clean cache
    this.lazyCheck();

    synchronized (this.internal) {
      return this.internal.containsKey(key);
    }
  }

  /**
   * Forcibly invalidate a key even if it is considered to be in use. Note that this will NOT cause
   * the post-removal function to be run.
   *
   * @param key key to invalidate
   */
  public void invalidate(final @NotNull K key) {
    synchronized (this.internal) {
      if (!this.internal.containsKey(key)) {
        // Value either not present or cleaned by lazy check. Either way, we're good
        return;
      }

      // Remove stored object
      this.internal.remove(key);

      // Remove expiration entry - prevents more work later, plus prevents issues with values
      // invalidating early
      this.expiry.entries().removeIf(entry -> entry.getValue().equals(key));
    }

    // Run lazy check to clean cache
    this.lazyCheck();
  }

  /**
   * Forcibly expire all keys, requiring them to be in use to be kept.
   */
  public void expireAll() {
    synchronized (this.internal) {
      this.expiry.clear();
      this.internal.keySet().forEach(key -> this.expiry.put(0L, key));
    }

    this.lastLazyCheck.set(0);

    this.lazyCheck();
  }

  /**
   * Invalidate all expired keys that are not considered in use. If a key is expired but is
   * considered in use by the provided Function, its expiration time is reset.
   */
  private void lazyCheck() {
    long now = clock.millis();

    if (lastLazyCheck.get() > now - lazyFrequency) {
      return;
    }

    lastLazyCheck.set(now);

    synchronized (this.internal) {
      SortedMap<Long, Collection<K>> subMap = this.expiry.asMap().headMap(now);
      Collection<K> keys = subMap.values().stream()
          .collect(ArrayList::new, ArrayList::addAll, ArrayList::addAll);

      // Wipe original map
      subMap.clear();

      long nextExpiry = now + this.retention;

      keys.forEach(key -> {

        V value = this.internal.get(key);
        if (value != null && this.inUseCheck != null && this.inUseCheck.test(key, value)) {
          this.expiry.put(nextExpiry, key);
          return;
        }

        this.internal.remove(key);

        if (value == null) {
          return;
        }

        if (this.postRemoval != null) {
          this.postRemoval.accept(key, value);
        }
      });
    }
  }

}
