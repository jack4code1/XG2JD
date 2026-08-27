# Seckill Redis Key Migration

This migration changes the live seckill data contract from the legacy
`seckill:coupon:{id}` Hash to separated activity and stock keys. Do not run old
and new Lua scripts against the same campaign at the same time.

## Preconditions

1. Pause the seckill submission endpoint or route it to a maintenance response.
2. Record the legacy Hash `remain`, `status`, `start_time`, `end_time`, and
   `per_user_max`, plus all members of `seckill:user:{id}`.
3. Verify no pending message for that campaign is being published.

## Copy Contract

For each coupon id, create:

```text
seckill:activity:{id}    Hash: status/start_time/end_time/per_user_max
seckill:stock:{id}       String: legacy remain
seckill:users:{id}       Set: copied legacy users
```

Create `seckill:user-count:{id}` only when `per_user_max` is greater than one;
initialize each copied claimant with its known historical count. The pending
keys are created by the new Lua script only after the cutover.

## Verification and Cutover

1. Compare legacy `remain` to `GET seckill:stock:{id}`.
2. Compare `SCARD seckill:user:{id}` to `SCARD seckill:users:{id}`.
3. Confirm the activity Hash has all four required fields and timestamps are
   epoch milliseconds.
4. Deploy the new application version, then restore submission traffic.
5. Keep legacy keys read-only for at least 24 hours. This application never
   deletes them; remove them only through a separately approved operations task.
