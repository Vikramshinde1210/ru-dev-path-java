package com.redislabs.university.RU102J.dao;

import redis.clients.jedis.*;

import java.util.UUID;

public class RateLimiterSlidingDaoRedisImpl implements RateLimiter {

    private final JedisPool jedisPool;
    private final long windowSizeMS;
    private final long maxHits;

    public RateLimiterSlidingDaoRedisImpl(JedisPool pool, long windowSizeMS,
                                          long maxHits) {
        this.jedisPool = pool;
        this.windowSizeMS = windowSizeMS;
        this.maxHits = maxHits;

        try (Jedis jedis = pool.getResource()) {
            jedis.ping();            // opens the connection
        }
        UUID.randomUUID();           // initializes SecureRandom
    }

    // Challenge #7
    @Override
    public void hit(String name) throws RateLimitExceededException {
        try (Jedis jedis = jedisPool.getResource()) {
            String uuid = UUID.randomUUID().toString();   // slow on first call, so do it first
            String key = getKey(name);

            Pipeline p = jedis.pipelined();
            p.multi();

            long now = System.currentTimeMillis();        // take the timestamp last
            long cutoff = now - windowSizeMS;

            p.zadd(key, now, now + "-" + uuid);
            p.zremrangeByScore(key, "-inf", String.valueOf(cutoff));
            Response<Long> hits = p.zcard(key);
            p.exec();
            p.sync();

            if (hits.get() > maxHits) {
                throw new RateLimitExceededException();
            }
        }
    }

    private String getKey(String name) {
        return RedisSchema.getRateLimiterSlidingKey(
                name,
                windowSizeMS,
                maxHits
        );
    }
}