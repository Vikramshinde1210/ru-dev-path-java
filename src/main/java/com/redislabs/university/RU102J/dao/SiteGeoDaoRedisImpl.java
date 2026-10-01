package com.redislabs.university.RU102J.dao;

import com.redislabs.university.RU102J.api.Coordinate;
import com.redislabs.university.RU102J.api.GeoQuery;
import com.redislabs.university.RU102J.api.Site;
import redis.clients.jedis.*;

import java.util.*;
import java.util.stream.Collectors;

public class SiteGeoDaoRedisImpl implements SiteGeoDao {
    private JedisPool jedisPool;
    final static private Double capacityThreshold = 0.2;

    public SiteGeoDaoRedisImpl(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @Override
    public Site findById(long id) {
        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> fields =
                    jedis.hgetAll(RedisSchema.getSiteHashKey(id));
            if (fields == null || fields.isEmpty()) {
                return null;
            }
            return new Site(fields);
        }
    }

    // notes
    // with large sorted sets, consider ZSCAN for iterative retrieval
    // optimize multiple HGETALL round trips with pipelining
//    @Override
//    public Set<Site> findAll() {
//        try (Jedis jedis = jedisPool.getResource()) {
//            Set<String> keys = jedis.zrange(RedisSchema.getSiteGeoKey(), 0, -1);
//            Set<Site> sites = new HashSet<>(keys.size());
//            for (String key : keys) {
//                Map<String, String> site = jedis.hgetAll(key);
//                if (!site.isEmpty()) {
//                    sites.add(new Site(site));
//                }
//            }
//            return sites;
//        }
//    }


    // The goal is to replace the one HGETALL round trip per site with a pipeline, so all HGETALL commands are sent together
    @Override
    public Set<Site> findAll() {
        try (Jedis jedis = jedisPool.getResource()) {

            Set<String> keys =
                    jedis.zrange(RedisSchema.getSiteGeoKey(), 0, -1);

            Set<Site> sites = new HashSet<>(keys.size());

            Pipeline pipeline = jedis.pipelined();

            Map<String, Response<Map<String, String>>> responses =
                    new HashMap<>(keys.size());

            // Queue all HGETALL commands
            for (String key : keys) {
                responses.put(key, pipeline.hgetAll(key));
            }

            // Execute all queued commands
            pipeline.sync();

            // Read the responses
            for (String key : keys) {
                Map<String, String> site = responses.get(key).get();

                if (!site.isEmpty()) {
                    sites.add(new Site(site));
                }
            }

            return sites;
        }
    }

    @Override
    public Set<Site> findByGeo(GeoQuery query) {
        if (query.onlyExcessCapacity()) {
            return findSitesByGeoWithCapacity(query);
        } else {
            return findSitesByGeo(query);
        }
    }

    // Challenge #5
//     private Set<Site> findSitesByGeoWithCapacity(GeoQuery query) {
//         return Collections.emptySet();
//     }
    // Comment out the above, and uncomment what's below
    private Set<Site> findSitesByGeoWithCapacity(GeoQuery query) {
        Set<Site> results = new HashSet<>();
        Coordinate coord = query.getCoordinate();
        Double radius = query.getRadius();
        GeoUnit radiusUnit = query.getRadiusUnit();

         try (Jedis jedis = jedisPool.getResource()) {
             // START Challenge #5
             // Challenge #5: Get the sites matching the geo query, store them

             // 1. Find sites within the geographic radius
              List<GeoRadiusResponse> radiusResponses = jedis.georadius(
                      RedisSchema.getSiteGeoKey(),
                      coord.getLng(),
                      coord.getLat(),
                      radius,
                      radiusUnit
              );

             // 2. Get Site objects
             Set<Site> sites = radiusResponses.stream()
                     .map(response -> jedis.hgetAll(response.getMemberByString()))
                     .filter(fields -> fields != null && !fields.isEmpty())
                     .map(Site::new)
                     .collect(Collectors.toSet());


             // START Challenge #5
             // 3. Pipeline capacity lookups
             Pipeline pipeline = jedis.pipelined();
             Map<Long, Response<Double>> scores = new HashMap<>(sites.size());
             // Challenge #5: Add the code that populates the scores HashMap...
             for (Site site : sites) {

                 Response<Double> response =
                         pipeline.zscore(
                                 RedisSchema.getCapacityRankingKey(),
                                 String.valueOf(site.getId())
                         );

                 scores.put(site.getId(), response);
             }

             // Execute all queued commands
             pipeline.sync();

             // 4. Filter sites based on capacity
             for (Site site : sites) {

                 Double capacity = scores.get(site.getId()).get();

                 if (capacity != null && capacity >= capacityThreshold) {
                     results.add(site);
                 }
             }
         }

         return results;
    }

    private Set<Site> findSitesByGeo(GeoQuery query) {
        Coordinate coord = query.getCoordinate();
        Double radius = query.getRadius();
        GeoUnit radiusUnit = query.getRadiusUnit();

        try (Jedis jedis = jedisPool.getResource()) {
            List<GeoRadiusResponse> radiusResponses =
                    jedis.georadius(RedisSchema.getSiteGeoKey(), coord.getLng(),
                            coord.getLat(), radius, radiusUnit);

            return radiusResponses.stream()
                    .map(response -> jedis.hgetAll(response.getMemberByString()))
                    .filter(Objects::nonNull)
                    .map(Site::new).collect(Collectors.toSet());
        }
    }

    @Override
    public void insert(Site site) {
         try (Jedis jedis = jedisPool.getResource()) {
             String key = RedisSchema.getSiteHashKey(site.getId());
             jedis.hmset(key, site.toMap());

             if (site.getCoordinate() == null) {
                 throw new IllegalArgumentException("Coordinate required for Geo " +
                         "insert.");
             }
             Double longitude = site.getCoordinate().getGeoCoordinate().getLongitude();
             Double latitude = site.getCoordinate().getGeoCoordinate().getLatitude();
             jedis.geoadd(RedisSchema.getSiteGeoKey(), longitude, latitude,
                     key);
         }
    }
}
