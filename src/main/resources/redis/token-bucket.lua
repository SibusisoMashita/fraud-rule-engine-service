-- One authenticated principal/route key; milliseconds and Redis wall time.
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local state = redis.call('HMGET', KEYS[1], 'tokens', 'time')
local tokens = capacity
local previous = now
if state[1] or state[2] then
    tokens = tonumber(state[1])
    previous = tonumber(state[2])
    if not tokens or not previous or tokens < 0 then error('Invalid quota state') end
    now = math.max(now, previous)
    tokens = math.min(capacity, tokens + math.min(now - previous, math.ceil(capacity * 1000 / rate)) * rate / 1000)
end
local allowed = 0
local retry = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    retry = math.ceil((1 - tokens) * 1000 / rate)
end
redis.call('HSET', KEYS[1], 'tokens', tokens, 'time', now)
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * 1000 / rate) + 1)
return {allowed, retry}
