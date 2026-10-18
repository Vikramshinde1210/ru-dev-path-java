# Redis Java Developer Associate --- Comprehensive Exam Preparation Guide

> **Purpose:** A practical, command-first study guide for Redis concepts
> and Java/Jedis development. It combines the Redis questions and
> hands-on topics you've been studying: AOF rewrite, Jedis, hashes and
> sets, transactions vs Lua, pipelines, connection pools and timeouts,
> Streams, scanning, sorted sets, sliding-window rate limiting,
> geospatial commands, RedisTimeSeries, Docker, and performance
> troubleshooting.
>
> **Version note:** Redis command behavior can vary by server version
> and module availability. Examples target modern Redis (Redis 7.x/8.x
> where applicable) and Jedis-style Java code. Check the version and
> your course's exact exam objectives before the exam.

## How to use this guide

1.  Read the concept and the "why this, not that?" comparison.
2.  Run the Redis CLI examples against a disposable local instance.
3.  Predict the output before executing each command.
4.  Re-implement the Java examples with your course's Jedis version.
5.  Use the final checklist and mock questions for revision.

------------------------------------------------------------------------

# 1. Redis mental model

Redis is an in-memory data-structure server. A client sends commands;
Redis executes each command on the server, typically on its main
command-execution thread, and sends back a response. Many commands are
fast because the data structures are in memory and commands operate
directly on those structures.

Redis is not simply a key-value store where every value is a string. A
key points to a value of a particular data type.

  -----------------------------------------------------------------------
  Type                    Think of it as          Common use
  ----------------------- ----------------------- -----------------------
  String                  One binary-safe value   Counters, tokens, cache
                                                  values, serialized
                                                  payloads

  Hash                    Fields within one key   Object-like records,
                                                  e.g. a site or user

  List                    Ordered sequence        Queues, recent items,
                                                  limited list workflows

  Set                     Unique unordered        Membership, tags,
                          members                 deduplication

  Sorted set (ZSET)       Unique members ordered  Rankings, time windows,
                          by score                delayed work

  Stream                  Append-only event log   Event ingestion,
                                                  consumer groups

  Bitmap                  Bit positions in a      Compact boolean flags
                          string                  

  HyperLogLog             Approximate distinct    Approximate unique
                          counter                 visitors

  Geo index               Geospatial operations   Nearby locations and
                          backed by               distance
                          sorted-set-like         
                          encoding                

  RedisTimeSeries module  Timestamped numeric     Metrics and time-series
                          samples                 retention/aggregation
  -----------------------------------------------------------------------

Redis keys are binary-safe, but simple namespaced text keys are easiest
to operate:

``` text
site:123
user:42:profile
rate-limit:customer-42
events:payments
```

Use a consistent naming convention. Colons are a convention, not a
special Redis syntax.

## 1.1 Check the server and inspect keys

``` redis
PING
INFO server
INFO memory
DBSIZE
SCAN 0 MATCH site:* COUNT 100
TYPE site:123
EXISTS site:123
TTL site:123
```

-   `PING` checks connectivity; it does not prove that every application
    operation is healthy.
-   `INFO` exposes server statistics and configuration-related
    information.
-   `DBSIZE` counts keys in the selected logical database.
-   `SCAN` iterates keys incrementally.
-   `TYPE` tells you the value type.
-   `EXISTS` checks existence.
-   `TTL` returns remaining expiry seconds: `-2` means missing key; `-1`
    means key exists without expiry.

Avoid `KEYS *` in production: it scans the keyspace in one blocking
command and can create latency spikes. Use `SCAN` for incremental
discovery, while remembering that a scan is not a transactional
snapshot.

## 1.2 Logical databases are not the same as isolation

`SELECT 0` / `SELECT 1` switches a connection's logical database on
standalone Redis configurations that support numbered databases. These
databases share the same server resources; they are not a substitute for
separate deployments or strong tenant isolation. Redis Cluster supports
only database 0.

------------------------------------------------------------------------

# 2. Data structures and choosing the right one

## 2.1 Strings and counters

``` redis
SET greeting "hello"
GET greeting
SET session:abc "token-value" EX 3600
TTL session:abc
INCR page:views
INCRBY page:views 10
DECR page:views
MSET config:theme dark config:language en
MGET config:theme config:language
SET cache:item "value" NX EX 60
```

-   `SET key value EX seconds` stores a value with expiry.
-   `NX` means set only if the key does not exist. `XX` means set only
    if it already exists.
-   `INCR` is atomic as a single command and is useful for counters.
-   `MGET` reduces client/server round trips compared with issuing many
    individual `GET`s.

**Why not read, increment in Java, then write?** Two clients can read
the same old value and overwrite each other's updates. Use `INCR` for
simple counters because the read-modify-write occurs as one server
command.

## 2.2 Hashes: object-like records

``` redis
HSET site:101 name "North" status "active" visits 0
HGET site:101 name
HMGET site:101 name status
HGETALL site:101
HINCRBY site:101 visits 1
HEXISTS site:101 status
HDEL site:101 status
HLEN site:101
```

A hash stores fields under one Redis key. This can be convenient for a
Java domain object:

``` text
site:101 -> {name: "North", status: "active", visits: "12"}
```

**Why a hash instead of a JSON string?** - A hash lets you update/read
selected fields without fetching and rewriting the entire serialized
object. - A JSON string may be more natural for nested documents, but
field-level updates may require application-side read/modify/write
unless using a JSON module. - A hash is not automatically a relational
table: there are no joins, foreign keys, or schema enforcement.

**Important:** Redis hash values are strings at the protocol level.
Convert numbers in Java when reading them. `HGETALL` is (O(N)) in the
number of fields. It is reasonable for small records; avoid repeatedly
fetching enormous hashes when only one field is needed.

### Hash plus set index pattern

A course project may store objects in hashes and maintain a set of IDs:

``` redis
SADD sites:ids 101 102 103
HSET site:101 name North status active
HSET site:102 name South status inactive
SMEMBERS sites:ids
HGETALL site:101
```

The set answers "which site IDs exist?" and each hash stores the record.
This is a manually maintained index: create/delete/update logic must
keep the set and hashes consistent. A set does not automatically index
every key that begins with `site:`.

## 2.3 Lists

``` redis
LPUSH jobs job-A job-B
RPUSH jobs job-C
LRANGE jobs 0 -1
LLEN jobs
LPOP jobs
RPOP jobs
LTRIM recent 0 99
```

Lists preserve order and can support simple queue patterns. `LLEN`
returns list length. `LRANGE key 0 -1` returns the entire list, which
can be expensive for a very large list.

**List vs Stream:** Lists are useful for straightforward sequences and
queue-like workflows. Streams add IDs, event history, consumer groups,
acknowledgements, and pending-message tracking---better for durable
event-processing workflows where those features are needed.

## 2.4 Sets

``` redis
SADD tags redis java redis
SMEMBERS tags
SISMEMBER tags java
SCARD tags
SREM tags java
SINTER team:backend team:oncall
SUNION team:backend team:platform
SDIFF team:all team:inactive
```

Set members are unique and unordered. Re-adding an existing member does
not create a duplicate. `SADD` returns the number of newly added
members, not the final set size.

**`SMEMBERS` vs `SSCAN`:** - `SMEMBERS key` returns every member at
once. It is (O(N)) and can create a large response / latency spike for a
huge set. - `SSCAN key cursor [MATCH pattern] [COUNT count]` iterates
incrementally and is safer for large collections. - `COUNT` is a hint,
not a strict page-size guarantee. - A scan may return duplicates and may
not represent a snapshot while the set is changing. Clients should
tolerate duplicates where relevant.

Example scan loop in Java should continue until the returned cursor is
`"0"`; don't assume one call returns all values.

## 2.5 Sorted sets (ZSET)

Each member is unique and has a numeric score. Redis orders members by
score, with lexicographic ordering used to break equal-score ties.

``` redis
ZADD leaderboard 100 alice 150 bob 150 chandra
ZRANGE leaderboard 0 -1 WITHSCORES
ZREVRANGE leaderboard 0 2 WITHSCORES
ZSCORE leaderboard alice
ZCARD leaderboard
ZRANK leaderboard alice
ZREVRANK leaderboard alice
ZINCRBY leaderboard 10 alice
ZREM leaderboard bob
ZREMRANGEBYSCORE events -inf 1700000000
```

Key facts: - Adding the same member again updates its score; it does not
create another member. - `ZCARD` gives member count. - `ZRANGE` is
ascending by score in modern Redis. `ZREVRANGE` is descending. - `ZRANK`
is zero-based and ascending; `ZREVRANK` is zero-based and descending. -
`ZREMRANGEBYSCORE key min max` removes every member whose score is in
the inclusive range. Use `(` before a bound to make it exclusive,
e.g. `(100`.

**Why sorted set rather than list?** A ZSET supports ordering by score,
rank, and score-range removal. A list is better when insertion order is
the primary meaning and you do not need score-based queries.

**Why sorted set rather than set?** Use a set for uniqueness only; use a
sorted set when each member needs an ordering score.

## 2.6 Geospatial commands

Redis GEO commands store coordinates and provide radius/distance queries
using a sorted-set-based representation.

``` redis
GEOADD stores 72.8777 19.0760 mumbai 73.8567 18.5204 pune
GEODIST stores mumbai pune km
GEOSEARCH stores FROMLONLAT 72.8777 19.0760 BYRADIUS 200 km
GEOSEARCH stores FROMLONLAT 72.8777 19.0760 BYRADIUS 200 km WITHDIST ASC COUNT 10
```

Coordinates are longitude first, latitude second. Do not reverse them.
Use `GEOSEARCH` for modern radius/box searches; older `GEORADIUS`
commands are deprecated in favor of newer alternatives in modern Redis.

## 2.7 Other useful structures

``` redis
SETBIT daily:active:2026-10-18 42 1
GETBIT daily:active:2026-10-18 42
PFADD visitors:2026-10-18 user1 user2 user3
PFCOUNT visitors:2026-10-18
```

-   Bitmaps are compact when a large population has boolean states
    indexed by integer positions.
-   HyperLogLog estimates cardinality with small, fixed memory use but
    is approximate; do not use it when exact membership or exact counts
    are required.

------------------------------------------------------------------------

# 3. Expiration, eviction, and memory

## 3.1 Expiration

``` redis
SET otp:customer-7 123456 EX 300
EXPIRE user:42 3600
PEXPIRE user:42 60000
EXPIREAT report:daily 1792281600
PERSIST user:42
TTL otp:customer-7
PTTL otp:customer-7
```

Expiration is useful for cache entries, sessions, OTPs, and temporary
locks. It does not mean Redis runs a dedicated timer for every key.
Redis combines passive expiration (when keys are accessed) with active
expiration work.

Important: - `TTL` is in seconds; `PTTL` is in milliseconds. - `EXPIRE`
changes a key's expiry. - Replacing a key with a new `SET` normally
clears its old expiry unless an expiry option is included or the
relevant command option preserves it. - Expiration is not an exact
real-time scheduling mechanism; a key can remain in internal structures
briefly after its deadline, though it is treated as expired when
accessed.

## 3.2 Expiration vs eviction

These are different: - **Expiration:** a key's explicitly assigned TTL
reaches zero. - **Eviction:** Redis removes keys under memory pressure
according to the configured `maxmemory-policy`.

Common eviction policies include: - `noeviction`: reject writes that
need more memory. - `allkeys-lru` / `allkeys-lfu`: choose victims among
all keys based on approximated recency/frequency. - `volatile-lru` /
`volatile-lfu`: choose only keys with expiry set. - `allkeys-random` /
`volatile-random`. - `volatile-ttl`: favor keys with shorter remaining
TTL.

Check:

``` redis
INFO memory
CONFIG GET maxmemory
CONFIG GET maxmemory-policy
```

Use a policy based on workload. For a cache where any key may be
evicted, an `allkeys-*` policy can be appropriate. If only explicitly
expiring keys should be eligible, a `volatile-*` policy may fit. Don't
treat eviction as a replacement for capacity planning or persistence.

------------------------------------------------------------------------

# 4. Persistence: RDB, AOF, and durability trade-offs

Redis is in-memory, but it can persist data.

## 4.1 RDB snapshots

RDB stores point-in-time snapshots of the dataset.

Typical configuration:

``` redis
CONFIG GET save
```

In `redis.conf`, snapshot rules can look like:

``` text
save 900 1
save 300 10
save 60 10000
```

Conceptually, snapshot if the configured number of changes occur within
the corresponding interval. RDB files are compact and useful for backups
and faster restarts, but a crash can lose changes made after the last
snapshot.

## 4.2 AOF (Append Only File)

AOF records write operations so Redis can reconstruct the dataset by
replaying commands during recovery.

``` redis
CONFIG GET appendonly
CONFIG GET appendfsync
INFO persistence
```

Common `appendfsync` policies: - `always`: fsync after every write
operation (stronger durability, usually higher latency). - `everysec`:
fsync roughly once per second (common balance; a crash can lose about
the latest second of writes in adverse circumstances). - `no`: let the
operating system decide when to flush (often better throughput, weaker
durability guarantees).

`write()` to the OS page cache and `fsync()` to durable storage are not
the same thing. A successful Redis command response does not necessarily
mean the bytes have been fsynced to physical storage.

## 4.3 AOF rewrite: what happens to writes during rewrite?

This is a key exam and systems-design topic.

An AOF can grow large because it records a history of mutations. AOF
rewrite creates a more compact representation of the current dataset
rather than copying every historical command. Redis can perform rewrite
work in a child process while the parent continues serving clients.

Simplified sequence:

1.  Redis forks a child process for background rewrite.
2.  The child builds a compact base representation of the dataset at the
    fork's snapshot.
3.  The parent continues processing client writes.
4.  Writes occurring during the rewrite are still appended to the active
    incremental AOF and are also tracked for rewrite coordination
    (commonly described as a rewrite buffer / accumulated changes).
5.  When the child finishes, Redis ensures changes that happened after
    the fork are represented in the new AOF set.
6.  Redis switches to the new base-plus-incremental AOF set using the
    AOF manifest/atomic file-switching process. The old files can then
    be cleaned up.

The critical point: **writes do not simply stop while the rewrite child
builds the base file**. Redis continues serving writes, and post-fork
changes must be included in the final persisted state. The manifest
makes the multi-file AOF set manageable during the transition.

Modern Redis 7 uses a multi-part AOF layout with a base file and
incremental AOF files, described by a manifest. This allows rewrite to
create a new base while new writes continue to be recorded in
incremental files.

### Why not rewrite the AOF in place?

An in-place rewrite could conflict with ongoing appends and risk losing
or corrupting changes. Building a replacement base and coordinating
incremental writes enables Redis to continue serving traffic and switch
to the new set safely.

### Why fork / copy-on-write?

Fork lets the child see a consistent view of the dataset at the time of
fork while the parent continues changing its own logical state. The
operating system initially shares memory pages and copies pages when
modified (copy-on-write). A rewrite can still consume CPU, disk
bandwidth, and extra memory due to page copying; it is not free.

### AOF vs RDB

  -----------------------------------------------------------------------
  Question                RDB                     AOF
  ----------------------- ----------------------- -----------------------
  What is stored?         Dataset snapshot        Write history /
                                                  replayable operations

  Restart speed           Often faster to load    Can take longer due to
                                                  replay

  Data-loss window        Since last snapshot     Depends heavily on
                                                  fsync policy

  File size               Often compact           Can grow; rewrite
                                                  compacts it

  Good for                Backups, compact        Better durability
                          snapshots               granularity

  Can use both?           Yes                     Yes
  -----------------------------------------------------------------------

Choose based on recovery point objective (how much data loss is
acceptable), recovery time objective, disk/CPU budget, and operational
needs. Using both may provide complementary benefits.

## 4.4 Persistence troubleshooting

``` redis
INFO persistence
INFO stats
INFO memory
```

Look for: - Background save / AOF rewrite in progress or failures. -
Last persistence status and error information. - AOF size and rewrite
activity. - Memory pressure and fork-related latency.

In Docker, make sure `/data` is backed by a persistent volume. A
container's writable layer may be lost when the container is removed.

------------------------------------------------------------------------

# 5. Atomicity, transactions, WATCH, and Lua

## 5.1 One command is atomic; a sequence is not automatically atomic

A single Redis command such as `INCR` executes atomically relative to
other commands. But this sequence is unsafe under concurrent clients:

``` text
GET balance
Java: balance = balance - amount
SET balance newBalance
```

Two clients can read the same old value and overwrite one another. Use a
transaction, Lua script, or a suitable single command depending on the
invariant.

## 5.2 MULTI / EXEC

``` redis
MULTI
SET account:1 "100"
INCR account:2
EXEC
```

Commands queued after `MULTI` run when `EXEC` is issued. Other clients
do not interleave commands into the execution of that transaction.

Important nuance: Redis transactions do not work exactly like SQL
transactions. There is no general rollback of already executed commands
because a runtime error occurs in one queued command. Validate inputs
and understand command errors. `DISCARD` abandons a transaction before
`EXEC`.

### Java / Jedis illustration

``` java
try (Jedis jedis = jedisPool.getResource()) {
    Transaction tx = jedis.multi();
    tx.set("account:1", "100");
    tx.incr("account:2");
    List<Object> replies = tx.exec();
}
```

Exact generics and method signatures can differ by Jedis version. The
core concept is queue commands, then execute.

## 5.3 WATCH: optimistic concurrency

`WATCH` marks keys for optimistic concurrency control. If a watched key
changes before `EXEC`, the transaction is aborted (the execution result
indicates no successful transaction). This is useful for "read,
calculate, conditionally update" workflows.

Conceptual CLI:

``` redis
WATCH inventory:sku-1
GET inventory:sku-1
MULTI
DECR inventory:sku-1
EXEC
```

This is only safe if the application checks whether `EXEC` succeeded and
retries/handles conflict appropriately. `WATCH` is connection-specific
and should be managed carefully when using a pool.

## 5.4 Lua scripts: atomic read/compare/write

A Lua script executes atomically on Redis: other clients do not
interleave commands while the script runs. Use it when a business
invariant needs a conditional read and write.

Example: decrement stock only when stock is positive:

``` lua
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then
  return 0
end
redis.call('DECR', KEYS[1])
return 1
```

Run via CLI:

``` redis
SET inventory:sku-1 5
EVAL "local n=tonumber(redis.call('GET',KEYS[1]) or '0'); if n<=0 then return 0 end; redis.call('DECR',KEYS[1]); return 1" 1 inventory:sku-1
```

In Java, Jedis supports `eval` with keys and arguments; the exact
overload varies by version.

**Transaction vs Lua:** - `MULTI/EXEC`: group a known set of commands;
commands are queued and executed together. - `WATCH + MULTI/EXEC`:
optimistic concurrency, with application retries on conflicts. - Lua:
conditional logic, read/compute/write within one server-side atomic
execution; avoids several client/server round trips. - A single built-in
command is best when it already provides the needed atomic behavior.

**Why not Lua for everything?** Scripts block other command execution
while they run. Keep scripts short and bounded; do not run long loops
over huge data sets.

## 5.5 MULTI vs pipeline

They solve different problems: - **Transaction:** atomic execution of
queued commands relative to other clients. - **Pipeline:** send multiple
commands without waiting for each response, reducing network round
trips. It does not by itself make commands atomic. - A pipeline can
contain commands that execute interleaved with other clients. - A
transaction can reduce interleaving but does not automatically solve
every read-modify-write conflict; use `WATCH` or Lua where needed.

------------------------------------------------------------------------

# 6. Pipelining, batching, and network round trips

If a client sends 100 commands one by one and waits for each response,
it may pay roughly 100 request/response round trips. With pipelining,
the client sends a batch and reads the responses together, reducing
latency overhead.

Jedis-style example:

``` java
try (Jedis jedis = jedisPool.getResource()) {
    Pipeline p = jedis.pipelined();
    for (int i = 0; i < 100; i++) {
        p.set("batch:" + i, "value-" + i);
    }
    p.sync();
}
```

Important: - Pipeline responses must be consumed/synchronized according
to the client API. - Large pipelines can use a lot of client and server
memory; use bounded batches. - Pipeline is not a transaction. - Use
`MGET`, `MSET`, or multi-field commands when they express the operation
cleanly; these may be simpler than a pipeline of individual commands. -
Pipeline when many independent commands need to be sent and network
latency is a bottleneck.

### Choosing between approaches

  -----------------------------------------------------------------------
  Need                                Prefer
  ----------------------------------- -----------------------------------
  One counter increment               `INCR`

  Read many known string keys         `MGET`

  Write many known string keys        `MSET` where applicable

  Reduce round trips for many         Pipeline
  independent commands                

  Group commands without interleaving `MULTI/EXEC`

  Conditional read-modify-write       Lua or `WATCH` + transaction

  Bulk-load a large dataset           Bounded pipeline/bulk import
                                      strategy, with memory and
                                      durability impact monitored
  -----------------------------------------------------------------------

------------------------------------------------------------------------

# 7. Java development with Jedis

Jedis is a Java Redis client. It sends Redis commands over the network
and maps Redis replies to Java types. It is not the Redis server itself.

## 7.1 Use a connection pool for typical multi-threaded applications

``` java
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisExample {
    public static void main(String[] args) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        config.setMaxIdle(8);
        config.setMinIdle(2);

        try (JedisPool pool = new JedisPool(config, "localhost", 6379)) {
            try (Jedis jedis = pool.getResource()) {
                jedis.set("hello", "redis");
                System.out.println(jedis.get("hello"));
            }
        }
    }
}
```

This is representative code; constructors and configuration methods
differ across Jedis releases. Check your dependency version.

### Why `try-with-resources`?

It returns the borrowed connection to the pool even if an exception
occurs. Failing to close a borrowed Jedis resource can exhaust the pool.

### Pool sizing

A larger pool is not always faster. Too many simultaneous connections
can increase server/client overhead. Size based on concurrency, command
latency, throughput, connection limits, and measured pool wait time.
Monitor: - Active/idle connections. - Waiters / borrow wait time. -
Connection creation and validation failures. - Redis latency and CPU.

A pool is for reusing connections, not for making one connection safely
shareable among arbitrary concurrent threads.

## 7.2 Timeouts and network errors

A client timeout can mean: - Redis is slow or overloaded. - Network
latency or packet loss. - The application sent a huge
command/response. - Pool acquisition took too long. - A background
persistence operation increased latency. - Redis was unavailable or the
connection became unhealthy.

A timeout does **not** necessarily mean the command failed. The command
might have executed but the response did not reach the client in time.
Retrying non-idempotent operations such as `INCR` can double-apply them.
Design retries around operation semantics, idempotency keys, or
deduplication.

Troubleshooting sequence: 1. Check `PING` and basic connectivity. 2.
Check server health with `INFO`, `SLOWLOG GET`, and latency diagnostics.
3. Check pool exhaustion / borrow timeout. 4. Inspect command sizes and
response sizes. 5. Check Docker resource limits, persistence, CPU, and
disk I/O. 6. Use bounded pipelines for bulk writes rather than flooding
the server with huge bursts. 7. Tune connect, socket/read, and pool wait
timeouts according to the client version and application SLO.

Useful CLI:

``` redis
PING
INFO clients
INFO stats
INFO memory
SLOWLOG GET 10
LATENCY DOCTOR
```

Some latency commands require latency monitoring configuration and
supported server versions.

## 7.3 Java type conversion

Redis replies are not always the same type as Java domain fields. Hash
values are commonly returned as strings; parse and validate them:

``` java
String visitsText = jedis.hget("site:101", "visits");
long visits = visitsText == null ? 0L : Long.parseLong(visitsText);
```

Handle missing values explicitly. Do not assume every key/field exists.

## 7.4 Avoid N+1 network calls

Suppose there are 100 site IDs and you run `SMEMBERS sites:ids`, then
one `HGETALL` per site. That is 101 commands. If each `HGETALL` is a
separate round trip, network latency may dominate.

Options: - Pipeline the 100 `HGETALL` commands. - Store/index data
differently if the access pattern warrants it. - Fetch only required
fields using `HMGET`. - Use a carefully designed schema that avoids
giant responses.

Pipelining reduces round trips but doesn't eliminate the CPU or bytes
required to return all records.

------------------------------------------------------------------------

# 8. Scanning: SCAN, SSCAN, HSCAN, ZSCAN

Redis offers cursor-based iteration to avoid loading every element in a
single response.

``` redis
SCAN 0 MATCH user:* COUNT 100
SSCAN sites:ids 0 COUNT 100
HSCAN site:101 0 COUNT 100
ZSCAN leaderboard 0 COUNT 100
```

The reply includes a next cursor and a batch of items. Continue using
the returned cursor until it is `"0"`.

**Exam traps:** - `COUNT` is a hint, not a guarantee of exactly that
many results. - A scan may return duplicate elements. - Elements
added/removed during iteration mean it is not a consistent point-in-time
snapshot. - `SCAN` does not mean "page 1, page 2" with stable offsets. -
`SMEMBERS` is simpler for small sets; `SSCAN` is safer for very large
sets.

------------------------------------------------------------------------

# 9. Redis Streams and consumer groups

Streams are append-only logs of entries with IDs. Each entry contains
one or more field/value pairs.

## 9.1 Basic commands

``` redis
XADD payments * paymentId p-100 amount 250
XADD payments * paymentId p-101 amount 400
XRANGE payments - +
XREVRANGE payments + - COUNT 10
XLEN payments
XREAD COUNT 10 STREAMS payments 0
```

`XADD key * ...` lets Redis generate an ID. IDs usually look like
`<milliseconds>-<sequence>`. They are ordered stream identifiers, not
plain application sequence numbers.

## 9.2 Consumer groups

``` redis
XGROUP CREATE payments payment-workers 0 MKSTREAM
XREADGROUP GROUP payment-workers worker-1 COUNT 10 BLOCK 5000 STREAMS payments >
XACK payments payment-workers 1710000000000-0
XPENDING payments payment-workers
```

-   Consumer groups distribute work among consumers.
-   `>` asks for messages never delivered to another consumer in that
    group.
-   Delivered-but-not-acknowledged entries are tracked in the Pending
    Entries List (PEL).
-   `XACK` acknowledges processed entries; it does not delete them from
    the stream.
-   `XPENDING` helps inspect unacknowledged messages.
-   A consumer that crashes can leave pending messages requiring
    reclaim/recovery.

Modern Redis versions provide commands such as `XAUTOCLAIM` to claim
idle pending messages. Exact options depend on version.

**Why not a list?** Streams have IDs, history/range reads, consumer
groups, pending tracking, and acknowledgements. A list can be enough for
a basic queue but does not provide the same consumer-group model.

**At-least-once processing:** A consumer can process a message and crash
before acknowledging it, so the message may be delivered again. Make
handlers idempotent or deduplicate by event ID.

------------------------------------------------------------------------

# 10. Sliding-window rate limiter with sorted sets

This topic connects ZSET commands with atomicity, timestamps, and Java
implementation.

## 10.1 Basic algorithm

For a user allowed `maxHits` requests in the last one second: 1. Get
current time in milliseconds. 2. Remove entries older than the window.
3. Count entries still inside the window. 4. If count is at/above limit,
reject. 5. Otherwise add this request with a unique member and current
timestamp score. 6. Set a TTL so inactive users' keys eventually
disappear.

CLI sketch:

``` redis
ZREMRANGEBYSCORE limiter:user-42 -inf 1700000000000
ZCARD limiter:user-42
ZADD limiter:user-42 1700000001000 request-unique-id
PEXPIRE limiter:user-42 2000
```

For a window ending at `now` with duration `windowMs`, the lower bound
is `now - windowMs`. To represent a window that includes timestamps
strictly newer than the cutoff, remove scores `-inf` through the cutoff
inclusively. Be explicit about whether the boundary is inclusive in your
requirements.

## 10.2 Critical correctness issue: separate commands race

If you run prune, count, and add as separate commands, two concurrent
requests can both observe available capacity and both add, exceeding the
limit.

Use a Lua script to prune/count/check/add atomically. Each request needs
a unique member. If the timestamp alone is the member, requests in the
same millisecond can overwrite each other because ZSET members are
unique.

Example Lua script:

``` lua
-- KEYS[1] = limiter key
-- ARGV[1] = now in milliseconds
-- ARGV[2] = window size in milliseconds
-- ARGV[3] = max allowed requests
-- ARGV[4] = unique request ID
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local requestId = ARGV[4]
local cutoff = now - window

redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)
local count = redis.call('ZCARD', key)

if count >= limit then
  redis.call('PEXPIRE', key, window + 1000)
  return {0, count}
end

redis.call('ZADD', key, now, requestId)
redis.call('PEXPIRE', key, window + 1000)
return {1, count + 1}
```

This script defines the window as excluding timestamps at or before the
cutoff. If the intended boundary differs, adjust the pruning rule. Use a
robust unique request ID (UUID, monotonic unique token, or request ID)
and validate all inputs. The TTL is cleanup, not the actual rate-limit
logic.

## 10.3 Complexity and trade-offs

-   `ZADD`: (O(`\log `{=tex}N)) per member.
-   `ZCARD`: (O(1)).
-   `ZREMRANGEBYSCORE`: (O(`\log `{=tex}N + M)), where (M) is the number
    of removed members.
-   A sliding log is accurate but stores one member per request within
    the active window.
-   A fixed-window counter is cheaper but can allow bursts around window
    boundaries.
-   A token bucket / leaky bucket can offer a different traffic-shaping
    behavior, but requires a different state model.

**Do not confuse timestamp score and member identity.** The score
provides chronological ordering; the member must uniquely identify each
request.

------------------------------------------------------------------------

# 11. RedisTimeSeries module

RedisTimeSeries is a module (commonly included in Redis Stack
distributions) for storing and querying timestamped numeric samples. It
is not a core data type available in every bare Redis installation.

## 11.1 Start a Redis Stack container

Simple local learning setup:

``` bash
docker run -d --name redis-stack \
  -p 6379:6379 -p 8001:8001 \
  redis/redis-stack:latest
```

Port `6379` is Redis; `8001` is commonly RedisInsight's web interface in
the Stack image. For repeatable work, pin a version instead of relying
on `latest`.

Check module commands:

``` redis
MODULE LIST
TS.CREATE cpu:host-1 RETENTION 86400000 LABELS host host-1 metric cpu
TS.ADD cpu:host-1 * 0.42
TS.ADD cpu:host-1 1792300000000 0.51
TS.GET cpu:host-1
TS.RANGE cpu:host-1 - +
TS.MRANGE - + WITHLABELS FILTER metric=cpu
```

The timestamp is typically Unix epoch **milliseconds**, and the sample
value is numeric. In Java, use `System.currentTimeMillis()` or a
correctly converted `Instant`; don't accidentally pass epoch seconds
when milliseconds are expected.

`TS.CREATE` creates a series. `RETENTION` controls how long samples are
kept (milliseconds). Labels support filtering across series. `TS.RANGE`
queries one series; `TS.MRANGE` queries multiple series using label
filters. Aggregation/downsampling features depend on module version and
command options.

## 11.2 Why TimeSeries instead of a sorted set?

A ZSET can model samples with timestamps as scores and values encoded in
members, but this becomes awkward: - Timestamp scores are numeric
doubles, so precision and duplicate timestamps require careful
handling. - Values must be encoded into members, and duplicate
score/member behavior needs custom design. - Retention, labels,
aggregation, and time-series query semantics are more natural with
RedisTimeSeries. - TimeSeries requires the module; a ZSET works on core
Redis but requires more application logic.

Use ZSET for general ranking/time-ordering and RedisTimeSeries for
numeric telemetry when module support and its query model fit.

------------------------------------------------------------------------

# 12. Docker and local development

## 12.1 Redis 7 with AOF and a persistent volume

Example `docker-compose.yml`:

``` yaml
services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - redis_data:/data
volumes:
  redis_data:
```

Start / inspect:

``` bash
docker compose up -d
docker compose ps
docker compose logs -f redis
docker exec -it <container-name> redis-cli
```

Stop:

``` bash
docker compose down
```

`docker compose down -v` also removes named volumes and therefore
deletes persisted data in that volume. Do not run it if you want to
preserve your local dataset.

## 12.2 Why a volume matters

A container can be removed and recreated. If Redis data exists only in
the container's writable layer, removing the container may remove that
data. A named volume mapped to `/data` preserves it across container
recreation.

## 12.3 Common Java-on-Windows / Docker checks

If Java runs on Windows and Redis runs in Docker with port mapping
`6379:6379`, the application usually connects to `localhost:6379`. If
the Java app itself runs in another container, `localhost` refers to
that app container, not the Redis container; use the Compose service
name on the shared network.

Troubleshoot in this order: 1. `docker ps` --- is the container running?
2. `docker logs <container>` --- startup errors? 3.
`docker exec -it <container> redis-cli PING` --- server responds
internally? 4. From host, connect to `localhost:6379`. 5. Check
firewall, port mapping, host/port, credentials, TLS, and Redis version.
6. Inspect client logs and pool timeouts. 7. For write timeouts,
consider command outcome ambiguity before blindly retrying.

------------------------------------------------------------------------

# 13. Performance and operational diagnostics

## 13.1 Complexity matters

Typical command complexities to remember (confirm against the version's
command docs when necessary):

  -----------------------------------------------------------------------
  Command                             Typical complexity
  ----------------------------------- -----------------------------------
  `GET`, `SET`, `INCR`                (O(1))

  `HGET`                              (O(1)) average

  `HGETALL`                           (O(N)) in hash fields

  `LLEN`                              (O(1))

  `SCARD`, `ZCARD`                    (O(1))

  `SMEMBERS`                          (O(N))

  `ZRANGE`                            (O(`\log `{=tex}N + M)) for
                                      returned range in modern
                                      implementations

  `ZADD`                              (O(`\log `{=tex}N)) per member

  `ZREMRANGEBYSCORE`                  (O(`\log `{=tex}N + M))

  `SCAN` family                       Incremental; total work is
                                      proportional to collection/keyspace
                                      size

  `XADD`                              Typically (O(1)) per added entry,
                                      with details depending on options
  -----------------------------------------------------------------------

`N` usually refers to collection size; `M` often refers to the number of
returned or removed elements. Learn the complexity notation and the
practical response-size cost, not just the symbol.

## 13.2 Slowlog and latency

``` redis
SLOWLOG GET 10
SLOWLOG LEN
INFO commandstats
INFO stats
INFO clients
INFO memory
```

Slowlog records command execution time on the server, not the entire
end-to-end client latency. A command may have low server execution time
but high client-observed latency due to network delay, pool wait,
serialization, or response transfer.

Large `SMEMBERS`, `HGETALL`, `LRANGE 0 -1`, and broad range queries can
be expensive due to response size even if the core data operation is
efficient.

## 13.3 Pipelining is not a cure for every slow command

Pipelining reduces round-trip overhead, but it does not make a huge
command cheap. A pipeline containing 100 enormous `HGETALL` replies can
still consume substantial CPU, memory, and bandwidth. Fix access
patterns and response sizes first.

------------------------------------------------------------------------

# 14. Security and production hygiene

-   Use authentication/ACLs and least privilege; do not expose Redis
    publicly without a deliberate security design.
-   Use TLS where required by the environment.
-   Avoid embedding credentials in source control or logs.
-   Use key prefixes and clear ownership conventions.
-   Validate script inputs and bound script execution time.
-   Set TTLs for temporary data, but do not give permanent business
    records arbitrary short TTLs.
-   Treat client timeouts as ambiguous outcomes; use idempotency for
    retry-sensitive operations.
-   Monitor memory, latency, persistence status, connection count, and
    evictions.
-   Plan backups and test restore procedures; a backup that has never
    been restored is unverified.
-   Be careful with `FLUSHDB` and `FLUSHALL`: they delete data. Never
    run them against a shared or production instance.

------------------------------------------------------------------------

# 15. High-value "why not something else?" comparisons

  -----------------------------------------------------------------------
  Situation               Better fit              Why not the
                                                  alternative?
  ----------------------- ----------------------- -----------------------
  Unique membership only  Set                     List permits
                                                  duplicates; ZSET adds
                                                  unneeded score ordering

  Ranking by score        Sorted set              Set has no rank; list
                                                  doesn't naturally query
                                                  by score

  Read all small hash     `HGETALL`               Simple and one command
  fields                                          

  Read one hash field     `HGET`                  Avoid transfer of all
                                                  fields

  Iterate huge set        `SSCAN`                 `SMEMBERS` creates one
                                                  potentially huge
                                                  response

  Many independent        Pipeline                Sequential requests
  commands over                                   waste round trips
  high-latency network                            

  Need atomic group       `MULTI/EXEC`            Pipeline alone does not
  execution                                       guarantee atomicity

  Need conditional        Lua or `WATCH`          Separate client calls
  read-modify-write       transaction             race

  Simple atomic increment `INCR`                  Java read-modify-write
                                                  races

  Event processing with   Stream + consumer group List does not provide
  acknowledgements                                the same PEL/ack
                                                  semantics

  Compact point-in-time   RDB                     AOF history can be
  backup                                          larger

  Fine-grained write      AOF, with suitable      RDB may lose writes
  recovery                fsync policy            since the last snapshot

  Numeric time-series     RedisTimeSeries         ZSET requires custom
  analytics                                       encoding, retention,
                                                  and aggregation logic

  Cache entry expires by  TTL                     Eviction is
  age                                             memory-pressure driven,
                                                  not a specific key's
                                                  deadline

  Memory pressure cache   Eviction policy         TTL alone does not
  policy                                          guarantee memory stays
                                                  within maxmemory
  -----------------------------------------------------------------------

------------------------------------------------------------------------

# 16. Practice questions with answers

Try answering before opening each answer.

## Q1. What does `SADD tags redis java redis` return on an empty set?

**Answer:** `2`, because only two new unique members were added. The
duplicate `redis` member is not added twice.

## Q2. A ZSET has `alice` at score 10. You run `ZADD z 20 alice`. How many members exist?

**Answer:** One. `alice`'s score is updated to 20. Members are unique.

## Q3. Why can `SMEMBERS` be risky for a huge set?

**Answer:** It returns all members in one response and has (O(N))
work/transfer. Use `SSCAN` for incremental iteration when the set is
large.

## Q4. Does `SSCAN key 0 COUNT 100` guarantee exactly 100 members?

**Answer:** No. `COUNT` is a hint. Continue with the returned cursor
until it returns `0`; tolerate duplicates.

## Q5. What does `LLEN queue` return?

**Answer:** The number of list elements, in (O(1)) time.

## Q6. Is a pipeline atomic?

**Answer:** No. It reduces round trips. Other clients may execute
commands between pipelined commands. Use a transaction or Lua when
atomicity is required.

## Q7. Does `MULTI/EXEC` provide SQL-style rollback if one queued command has a runtime error?

**Answer:** No. Redis transactions queue and execute commands together
without interleaving, but they do not provide general rollback of
successfully executed commands due to a runtime error in another
command.

## Q8. When is Lua preferable to `WATCH`?

**Answer:** When a short conditional read/compute/write sequence should
execute atomically on the server and avoiding client round trips is
valuable. `WATCH` is useful for optimistic concurrency but requires
conflict detection/retry logic.

## Q9. Why should each rate-limit request have a unique ZSET member?

**Answer:** ZSET members are unique. If the member is only the
timestamp, requests in the same millisecond can overwrite each other and
undercount requests.

## Q10. What is the complexity of `ZCARD`?

**Answer:** (O(1)).

## Q11. Why can a Jedis command time out even if Redis executed it?

**Answer:** The command may have executed but the response may not have
arrived before the client's deadline. Retrying a non-idempotent
operation can apply it twice.

## Q12. What happens to writes while AOF rewrite is running?

**Answer:** Redis continues serving writes. The parent records new
writes in incremental AOF data and coordinates them with the rewrite so
the final base-plus-incremental AOF set includes changes after the fork.
The new set is switched in safely using the AOF manifest mechanism in
modern Redis.

## Q13. What does `appendfsync everysec` mean?

**Answer:** Redis attempts to fsync AOF data about once per second. It
is a common durability/performance compromise, but a crash can lose a
small recent window of writes.

## Q14. Why use a Docker volume for `/data`?

**Answer:** It preserves persisted Redis files when a container is
recreated or removed. Removing the volume itself deletes that stored
data.

## Q15. What is the difference between expiration and eviction?

**Answer:** Expiration is based on a key's TTL deadline. Eviction
removes keys because of memory pressure according to `maxmemory-policy`.

## Q16. Why use Redis Streams instead of a List for event processing?

**Answer:** Streams support IDs, history/range reads, consumer groups,
pending entries, and acknowledgements. Lists may be enough for a simpler
queue.

## Q17. Does `XACK` delete the stream entry?

**Answer:** No. It acknowledges the entry for a consumer group and
removes it from that group's pending tracking; the entry remains in the
stream until trimmed/deleted.

## Q18. Are RedisTimeSeries timestamps seconds or milliseconds?

**Answer:** Typically milliseconds since Unix epoch. Match the module's
expected units and avoid passing `Instant.getEpochSecond()` when
milliseconds are expected.

## Q19. Why is `HGETALL` not always the best choice?

**Answer:** It returns every field, so cost and response size grow with
the hash. Use `HGET` or `HMGET` when only a subset is needed.

## Q20. What is the difference between RDB and AOF?

**Answer:** RDB is a point-in-time snapshot; AOF records mutations for
replay. RDB is often compact and loads quickly; AOF can offer a smaller
data-loss window depending on fsync policy.

## Q21. Does `COUNT 10` in `XREADGROUP` guarantee exactly 10 messages?

**Answer:** It is a maximum/requested count for the read, not a
guarantee that 10 are available. Blocking and stream contents affect
what is returned.

## Q22. What should a client do if `EXEC` after `WATCH` aborts?

**Answer:** Treat it as a concurrency conflict and retry or return a
conflict according to the application's policy. Do not assume the
transaction committed.

## Q23. Why does a pool not mean a Jedis connection can be shared by all threads?

**Answer:** A pool lends connections to callers for bounded use.
Concurrently sharing one stateful connection can cause command/response
confusion and unsafe transaction/session interactions. Borrow one per
operation scope and close it.

## Q24. Why might a command have low SLOWLOG time but high application latency?

**Answer:** Pool waiting, network latency, client
scheduling/serialization, or transferring a large response may dominate;
SLOWLOG measures server command execution time, not all end-to-end time.

## Q25. What is the difference between `SET key value EX 60` and an eviction policy?

**Answer:** The `EX` option assigns a 60-second lifetime to that key. An
eviction policy chooses keys to remove under memory pressure.

------------------------------------------------------------------------

# 17. Hands-on lab checklist

Run these in a disposable local Redis environment.

## Lab A --- Strings and TTL

``` redis
SET lab:token abc EX 60
GET lab:token
TTL lab:token
SET lab:counter 0
INCR lab:counter
INCRBY lab:counter 5
```

Explain the TTL output and why `INCR` is safer than read/increment/write
in Java.

## Lab B --- Hash + set index

``` redis
DEL sites:ids site:101 site:102
SADD sites:ids 101 102
HSET site:101 name North status active visits 0
HSET site:102 name South status inactive visits 5
SMEMBERS sites:ids
HGETALL site:101
HINCRBY site:101 visits 1
```

Write a Java method that loads the IDs and retrieves the hash records.
Then improve it using a pipeline and explain what does---and does
not---become atomic.

## Lab C --- Set iteration

``` redis
SADD lab:members a b c d e
SMEMBERS lab:members
SSCAN lab:members 0 COUNT 2
```

Repeat using each returned cursor until `0`. Observe that the actual
count per response may differ from the hint.

## Lab D --- Sorted set behavior

``` redis
DEL lab:z
ZADD lab:z 10 alice 20 bob 20 chandra
ZADD lab:z 30 alice
ZCARD lab:z
ZRANGE lab:z 0 -1 WITHSCORES
ZRANK lab:z alice
ZREVRANK lab:z alice
```

Explain why the cardinality is still three and how equal scores are
ordered.

## Lab E --- Transaction vs pipeline

Run a `MULTI/EXEC` example and a Jedis pipeline example. Explain why the
first is a transaction and the second is primarily a network
optimization.

## Lab F --- Rate limiter

Implement the Lua script in Section 10. Send more than the allowed
number of requests inside the window. Then run concurrent requests and
verify the atomic script does not exceed the limit under the defined
semantics.

## Lab G --- AOF persistence

Start Redis with AOF enabled and a persistent volume. Write keys,
inspect `INFO persistence`, and learn where the AOF files live in the
container. Do not delete the volume unless you intend to reset the lab.

## Lab H --- Streams

Create a stream, create a consumer group, read with `XREADGROUP`,
inspect `XPENDING`, acknowledge with `XACK`, and observe pending state
change.

## Lab I --- RedisTimeSeries

Use `redis/redis-stack` and check `MODULE LIST`. Create a series, add
samples with millisecond timestamps, query a range, and filter by
labels.

------------------------------------------------------------------------

# 18. Last-minute exam revision sheet

Memorize these distinctions:

-   **String:** simple value/counter; `INCR` is atomic.
-   **Hash:** fields under one key; `HGETALL` is (O(N)).
-   **Set:** unique unordered members; `SADD` returns newly added count.
-   **Sorted set:** unique members + score ordering; re-adding a member
    updates its score.
-   **List:** ordered sequence; `LLEN` is (O(1)).
-   **Stream:** append-only entries, IDs, consumer groups, pending list,
    acknowledgements.
-   **`SCAN` family:** cursor iteration; `COUNT` is a hint; duplicates
    are possible.
-   **Pipeline:** fewer network round trips, not atomic.
-   **`MULTI/EXEC`:** grouped execution without interleaving; no general
    SQL-style rollback.
-   **`WATCH`:** optimistic concurrency; check `EXEC` and retry
    conflicts.
-   **Lua:** atomic server-side conditional logic; keep scripts short.
-   **Pool:** reuse connections; always return borrowed connections;
    size from measurements.
-   **Timeout:** command outcome may be ambiguous; retries can duplicate
    non-idempotent effects.
-   **RDB:** snapshots; compact, but changes since last snapshot may be
    lost.
-   **AOF:** replayable write history; durability depends on fsync
    policy; rewrite compacts history.
-   **AOF rewrite:** Redis continues serving writes; incremental changes
    are coordinated into the replacement AOF set.
-   **TTL:** age-based expiration.
-   **Eviction:** memory-pressure policy.
-   **ZSET rate limiter:** unique member per request; score = timestamp;
    prune/count/add atomically.
-   **RedisTimeSeries:** module, commonly milliseconds for timestamps,
    labels and time-series queries.
-   **Docker volume:** preserves `/data`; removing the volume deletes
    persisted data.
-   **`SLOWLOG`:** server execution time, not full client-observed
    latency.

## A 5-session revision plan

1.  **Data types and complexity:** strings, hashes, lists, sets, sorted
    sets, geo, TTL, scans.
2.  **Java client:** Jedis resource lifecycle, pool sizing, pipelines,
    transaction APIs, timeouts.
3.  **Atomicity:** `MULTI/EXEC`, `WATCH`, Lua, race conditions, rate
    limiter.
4.  **Persistence and operations:** RDB/AOF, rewrite, fsync, Docker
    volumes, diagnostics.
5.  **Advanced topics and practice:** Streams, RedisTimeSeries,
    geospatial commands, mock questions, and hands-on labs.

## Official references to verify against your course version

-   Redis commands reference: https://redis.io/docs/latest/commands/
-   Redis data types: https://redis.io/docs/latest/develop/data-types/
-   Redis persistence:
    https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/
-   Redis transactions:
    https://redis.io/docs/latest/develop/using-commands/transactions/
-   Redis programmability / Lua:
    https://redis.io/docs/latest/develop/programmability/eval-intro/
-   Redis Streams:
    https://redis.io/docs/latest/develop/data-types/streams/
-   Jedis repository and version-specific examples:
    https://github.com/redis/jedis
-   RedisTimeSeries documentation:
    https://redis.io/docs/latest/develop/data-types/timeseries/

> **Final exam tip:** Do not memorize command names alone. For every
> question, identify the data model, operation complexity, round trips,
> concurrency behavior, response size, and failure/retry semantics.
> Those distinctions are what separate "it works in a demo" from a
> correct Redis application.
