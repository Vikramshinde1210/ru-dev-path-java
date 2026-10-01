package com.redislabs.university.RU102J.dao;

import com.redislabs.university.RU102J.api.Site;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.*;

public class SiteDaoRedisImpl implements SiteDao {
    private final JedisPool jedisPool;

    public SiteDaoRedisImpl(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    // When we insert a site, we set all of its values into a single hash.
    // We then store the site's id in a set for easy access.
    @Override
    public void insert(Site site) {
        try (Jedis jedis = jedisPool.getResource()) {
            String hashKey = RedisSchema.getSiteHashKey(site.getId()); // sites:info:4
            String siteIdKey = RedisSchema.getSiteIDsKey(); // sites:ids
            jedis.hmset(hashKey, site.toMap());
            // Redis can accept other commands between these two commands
            // But not if these are executed within a transaction.
            jedis.sadd(siteIdKey, hashKey); // set containing all the site ids
        }
    }

    @Override
    public Site findById(long id) {
        try(Jedis jedis = jedisPool.getResource()) {
            String key = RedisSchema.getSiteHashKey(id);
            Map<String, String> fields = jedis.hgetAll(key);
            if (fields == null || fields.isEmpty()) {
                return null;
            } else {
                return new Site(fields);
            }
        }
    }

    // Challenge #1
    @Override
    public Set<Site> findAll() {
        try (Jedis jedis = jedisPool.getResource()) {

            String siteIdKey = RedisSchema.getSiteIDsKey();

            Set<String> siteKeys = jedis.smembers(siteIdKey);

            Set<Site> sites = new HashSet<>();

            for (String siteKey : siteKeys) {
                Map<String, String> fields = jedis.hgetAll(siteKey);

                if (fields != null && !fields.isEmpty()) {
                    sites.add(new Site(fields));
                }
            }

            return sites;
        }
    }
}
