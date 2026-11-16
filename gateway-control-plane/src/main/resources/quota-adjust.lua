if redis.call('EXISTS',KEYS[2])==1 then return 1 end
local revision=tonumber(redis.call('HGET',KEYS[1],'revision') or '0')
if revision~=tonumber(ARGV[1]) then return 0 end
local now=redis.call('TIME');local ms=now[1]*1000+math.floor(now[2]/1000)
local day=math.floor(ms/86400000);local z=day+719468;local era=math.floor(z/146097);local doe=z-era*146097
local yoe=math.floor((doe-math.floor(doe/1460)+math.floor(doe/36524)-math.floor(doe/146096))/365)
local y=yoe+era*400;local doy=doe-(365*yoe+math.floor(yoe/4)-math.floor(yoe/100))
local mp=math.floor((5*doy+2)/153);if mp>=10 then y=y+1 end;local month=tostring(y)..'-'..tostring(mp+(mp<10 and 3 or -9))
redis.call('HSET',KEYS[1],'day',day,'month',month)
redis.call('EXPIRE',KEYS[1],5356800)
redis.call('HSET',KEYS[1],'d',ARGV[2],'m',ARGV[3]);redis.call('HINCRBY',KEYS[1],'revision',1)
redis.call('SET',KEYS[2],'applied','EX',5356800)
return 1
