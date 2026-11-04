#!/usr/bin/env bash
# Verifies the running local infrastructure against the Milestone 2 acceptance criteria.
# Prints one PASS/FAIL line per check and exits non-zero if any check fails.
#
#   scripts/check-infra.sh
#
# Observability checks run automatically when the obs profile is up.
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

passed=0
failed=0
pass() {
  echo "PASS  $*"
  passed=$((passed + 1))
}
fail() {
  echo "FAIL  $*"
  failed=$((failed + 1))
}
check() { # check "<description>" <command...>
  local description="$1"
  shift
  if "$@" > /dev/null 2>&1; then pass "$description"; else fail "$description"; fi
}
# Retries a command for up to $1 seconds; backends need a moment to ingest data.
eventually() {
  local deadline=$((SECONDS + $1))
  shift
  until "$@" > /dev/null 2>&1; do
    ((SECONDS < deadline)) || return 1
    sleep 2
  done
}

require_docker
[[ -f "$ENV_FILE" ]] || die ".env not found; run scripts/dev-up.sh first"
SERVICES=(identity catalog order inventory payment notification sandbox)

container_running() { [[ "$(docker inspect -f '{{.State.Status}}' "$PROJECT-$1-1" 2> /dev/null)" == running ]]; }
container_healthy() { [[ "$(docker inspect -f '{{.State.Health.Status}}' "$PROJECT-$1-1" 2> /dev/null)" == healthy ]]; }
job_succeeded() { [[ "$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$PROJECT-$1-1" 2> /dev/null)" == "exited 0" ]]; }

# --- Containers --------------------------------------------------------------------------------
echo "-- containers"
for svc in postgres kafka redis mailpit; do
  check "$svc is healthy" container_healthy "$svc"
done
for job in postgres-init kafka-init; do
  check "$job completed successfully" job_succeeded "$job"
done

# Every published port must bind to 127.0.0.1 only.
exposed=$(docker ps --filter "label=com.docker.compose.project=$PROJECT" --format '{{.Names}} {{.Ports}}' |
  grep -E '(0\.0\.0\.0|\[::\]|:::):[0-9]+->' || true)
if [[ -z "$exposed" ]]; then pass "all published ports bind to 127.0.0.1"; else fail "ports exposed beyond localhost: $exposed"; fi

# --- Postgres: one database per service, and nothing else reachable ---------------------------
echo "-- postgres"
check "host can reach localhost:5432" port_in_use 5432

pg_env=()
for svc in "${SERVICES[@]}"; do
  var="$(tr '[:lower:]' '[:upper:]' <<< "$svc")_DB_PASSWORD"
  pg_env+=(-e "PW_$svc=$(env_value "$var")")
done

# Connections are made from a separate container on the compose network, which is the path the
# services use. (Connecting from inside the postgres container would hit initdb's loopback
# rules and not prove that passwords are checked.) One container runs the whole 7 x 8 matrix.
pg_client() {
  compose run --rm -T --no-deps -e PGHOST=postgres "$@" --entrypoint sh postgres-init -s 2> >(grep -v ' Container ' >&2)
}
matrix=$(pg_client "${pg_env[@]}" << 'SH'
dbs="identity_db catalog_db order_db inventory_db payment_db notification_db sandbox_db postgres"
for svc in identity catalog order inventory payment notification sandbox; do
  eval "pw=\$PW_$svc"
  for db in $dbs; do
    if out=$(PGPASSWORD="$pw" psql -U "${svc}_svc" -d "$db" -tAc 'select 1' 2>&1); then
      result=connected
    elif echo "$out" | grep -q 'permission denied for database'; then
      result=denied
    else
      result="error: $(echo "$out" | head -n 1)"
    fi
    echo "${svc}_svc $db $result"
  done
done
SH
)
while read -r role db result; do
  [[ -n "$role" ]] || continue
  if [[ "$db" == "${role%_svc}_db" ]]; then
    [[ "$result" == connected ]] && pass "$role can connect to its own $db" || fail "$role cannot connect to $db: $result"
  else
    [[ "$result" == denied ]] && pass "$role is refused by $db" || fail "$role on $db should be denied, got: $result"
  fi
done <<< "$matrix"

wrong_pw=$(pg_client <<< "PGPASSWORD=not-the-password psql -U order_svc -d order_db -tAc 'select 1' 2>&1" || true)
grep -q 'password authentication failed' <<< "$wrong_pw" && pass "a wrong password is rejected over the network" || fail "wrong password was not rejected: $wrong_pw"
loopback_pw=$(compose exec -T postgres sh -c "PGPASSWORD=not-the-password psql -h 127.0.0.1 -U order_svc -d order_db -tAc 'select 1' 2>&1" || true)
grep -q 'password authentication failed' <<< "$loopback_pw" && pass "a wrong password is rejected on loopback TCP too" ||
  fail "loopback TCP inside the postgres container skips passwords (volume created before --auth-host=scram-sha-256; recreate with dev-down --volumes): $loopback_pw"

superuser="$(env_value POSTGRES_SUPERUSER)"
privileged=$(compose exec -T postgres psql -U "$superuser" -d postgres -tAc \
  "select string_agg(rolname, ',') from pg_roles where rolname like '%\_svc' and (rolsuper or rolcreatedb or rolcreaterole or rolbypassrls or rolreplication)")
[[ -z "${privileged//[[:space:]]/}" ]] && pass "no service role has superuser, createdb, createrole, replication or bypassrls" || fail "over-privileged roles: $privileged"

ddl=$(pg_client -e "PGPASSWORD=$(env_value ORDER_DB_PASSWORD)" <<< \
  "psql -U order_svc -d order_db -v ON_ERROR_STOP=1 -tAc \"create table m2_probe (id int); drop table m2_probe; select 'ok'\" 2>&1")
[[ "$ddl" == *ok* ]] && pass "order_svc can create tables in order_db (needed by Flyway)" || fail "order_svc cannot run DDL in order_db: $ddl"

# --- Kafka -------------------------------------------------------------------------------------
echo "-- kafka"
check "host can reach localhost:9092" port_in_use 9092

# Runs in the kafka-init image, which has the Kafka CLI and jq.
kafka_report=$(compose run --rm -T --no-deps --entrypoint sh kafka-init -s 2> >(grep -v ' Container ' >&2) << 'SH'
set -u
B=kafka:29092
BIN=/opt/kafka/bin
export KAFKA_HEAP_OPTS=-Xmx256m
current=$("$BIN/kafka-topics.sh" --bootstrap-server $B --describe --exclude-internal)

jq -r '.deadLetter as $d | .topics[]
  | "\(.name) \(.partitions) \(.retentionDays * 86400000)",
    "\(.name)\($d.suffix) \(.partitions) \($d.retentionDays * 86400000)"' /config/topics.json |
while read -r name partitions retention; do
  s=$(echo "$current" | awk -v t="$name" '$1 == "Topic:" && $2 == t && /PartitionCount:/')
  p=$(echo "$s" | sed -n 's/.*PartitionCount: *\([0-9]*\).*/\1/p')
  r=$(echo "$s" | sed -n 's/.*retention\.ms=\([0-9]*\).*/\1/p')
  if [ "$p" = "$partitions" ] && [ "$r" = "$retention" ]; then
    echo "PASS topic $name has $partitions partitions and retention.ms=$retention"
  else
    echo "FAIL topic $name: expected $partitions partitions / retention.ms=$retention, found '${p:-missing}' / '${r:-missing}'"
  fi
done

listeners=$("$BIN/kafka-configs.sh" --bootstrap-server $B --describe --entity-type brokers --entity-name 1 --all | grep 'advertised.listeners=')
case "$listeners" in
  *EXTERNAL://localhost:9092*INTERNAL://kafka:29092* | *INTERNAL://kafka:29092*EXTERNAL://localhost:9092*)
    echo "PASS broker advertises kafka:29092 to containers and localhost:9092 to the host" ;;
  *) echo "FAIL advertised listeners are wrong: $listeners" ;;
esac

# Real produce/consume round trip on a throwaway topic.
t="infra-check-$(date +%s)"
"$BIN/kafka-topics.sh" --bootstrap-server $B --create --topic "$t" --partitions 1 --replication-factor 1 > /dev/null
echo "probe-$t" | "$BIN/kafka-console-producer.sh" --bootstrap-server $B --topic "$t" > /dev/null 2>&1
got=$("$BIN/kafka-console-consumer.sh" --bootstrap-server $B --topic "$t" --from-beginning --max-messages 1 --timeout-ms 15000 2> /dev/null)
"$BIN/kafka-topics.sh" --bootstrap-server $B --delete --topic "$t" > /dev/null
[ "$got" = "probe-$t" ] && echo "PASS produce and consume round trip" || echo "FAIL round trip returned '$got'"

# Auto-creation is off: writing to an unknown topic must fail and must not create it.
echo x | "$BIN/kafka-console-producer.sh" --bootstrap-server $B --topic infra-check-typo \
  --producer-property max.block.ms=4000 > /dev/null 2>&1
if "$BIN/kafka-topics.sh" --bootstrap-server $B --list | grep -qx infra-check-typo; then
  echo "FAIL producing to an unknown topic created it (auto-create is on)"
  "$BIN/kafka-topics.sh" --bootstrap-server $B --delete --topic infra-check-typo > /dev/null
else
  echo "PASS producing to an unknown topic does not create it"
fi
SH
)
while read -r verdict message; do
  case "$verdict" in
    PASS) pass "$message" ;;
    FAIL) fail "$message" ;;
  esac
done <<< "$kafka_report"
[[ -n "$kafka_report" ]] || fail "kafka checks produced no output"

# --- Redis -------------------------------------------------------------------------------------
echo "-- redis"
noauth=$(compose exec -T redis sh -c 'env -u REDISCLI_AUTH redis-cli ping' 2>&1 || true)
[[ "$noauth" == *NOAUTH* ]] && pass "redis rejects unauthenticated clients" || fail "redis answered without a password: $noauth"
pong=$(compose exec -T redis redis-cli ping | tr -d '\r')
[[ "$pong" == PONG ]] && pass "redis answers with the password" || fail "authenticated ping returned '$pong'"
policy=$(compose exec -T redis redis-cli config get maxmemory-policy | tail -n 1 | tr -d '\r')
[[ "$policy" == volatile-lru ]] && pass "maxmemory-policy is volatile-lru" || fail "maxmemory-policy is '$policy'"
aof=$(compose exec -T redis redis-cli config get appendonly | tail -n 1 | tr -d '\r')
[[ "$aof" == yes ]] && pass "append-only persistence is on" || fail "appendonly is '$aof'"

# --- Mailpit -----------------------------------------------------------------------------------
echo "-- mailpit"
marker="infra-check-$(random_secret | head -c 12)"
if curl -sS --max-time 10 "smtp://127.0.0.1:1025" --mail-from check@commerceflow.local \
  --mail-rcpt customer@example.com -T - > /dev/null 2>&1 << EOF; then
From: check@commerceflow.local
To: customer@example.com
Subject: $marker

Sent by scripts/check-infra.sh
EOF
  search=$(curl -sS --max-time 10 "http://127.0.0.1:8025/api/v1/search?query=subject:$marker")
  id=$(grep -o '"ID":"[^"]*"' <<< "$search" | head -n 1 | cut -d'"' -f4)
  if [[ -n "$id" ]]; then
    pass "an email sent over SMTP arrives in Mailpit"
    curl -sS --max-time 10 -X DELETE -H 'Content-Type: application/json' \
      -d "{\"IDs\":[\"$id\"]}" http://127.0.0.1:8025/api/v1/messages > /dev/null
  else
    fail "the test email did not reach Mailpit"
  fi
else
  fail "could not send email to localhost:1025"
fi

# --- Observability (only when the obs profile is running) --------------------------------------
if container_running prometheus; then
  echo "-- observability"
  grafana_auth="$(env_value GRAFANA_ADMIN_USER):$(env_value GRAFANA_ADMIN_PASSWORD)"
  grafana() { curl -sS --max-time 10 -u "$grafana_auth" "http://127.0.0.1:3000$1"; }
  prom_query() { curl -sS --max-time 10 --get --data-urlencode "query=$1" http://127.0.0.1:9090/api/v1/query; }

  check "prometheus is ready" curl -fsS --max-time 5 http://127.0.0.1:9090/-/ready
  all_targets_up() {
    local t
    t=$(curl -sS --max-time 5 http://127.0.0.1:9090/api/v1/targets?state=active)
    grep -q '"health":"up"' <<< "$t" && ! grep -qE '"health":"(down|unknown)"' <<< "$t"
  }
  if eventually 90 all_targets_up; then pass "every prometheus scrape target is up"; else
    fail "prometheus targets not all up: $(curl -sS http://127.0.0.1:9090/api/v1/targets?state=active | grep -oE '"(scrapeUrl|health|lastError)":"[^"]*"' | paste -sd ' ')"
  fi
  check "alert rules are loaded" bash -c "curl -sS http://127.0.0.1:9090/api/v1/rules | grep -q ScrapeTargetDown"
  partitions_metric() { prom_query 'kafka_topic_partitions{topic="order.events.v1"}' | grep -q '"6"\]'; }
  if eventually 60 partitions_metric; then pass "kafka-exporter reports 6 partitions for order.events.v1"; else fail "kafka-exporter metric for order.events.v1 missing or wrong"; fi

  check "grafana is healthy" bash -c "curl -fsS --max-time 5 http://127.0.0.1:3000/api/health | grep -q '\"database\": *\"ok\"'"
  for ds in prometheus tempo loki; do
    ds_ok() { grafana "/api/datasources/uid/$1/health" | grep -q '"status":"OK"'; }
    if eventually 60 ds_ok "$ds"; then pass "grafana datasource $ds is healthy"; else fail "grafana datasource $ds: $(grafana "/api/datasources/uid/$ds/health")"; fi
  done
  check "infrastructure dashboard is provisioned" bash -c "curl -fsS -u '$grafana_auth' http://127.0.0.1:3000/api/dashboards/uid/cf-infra-overview > /dev/null"

  # End-to-end telemetry: send a span, a log record and a metric through the collector and find
  # each one in its backend.
  trace_id=$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')
  span_id=$(od -An -tx1 -N8 /dev/urandom | tr -d ' \n')
  now_ns="$(date +%s)000000000"
  resource='{"attributes":[{"key":"service.name","value":{"stringValue":"infra-check"}}]}'
  otlp() { curl -fsS --max-time 10 -H 'Content-Type: application/json' -d "$2" "http://127.0.0.1:4318/v1/$1"; }

  if otlp traces "{\"resourceSpans\":[{\"resource\":$resource,\"scopeSpans\":[{\"scope\":{\"name\":\"check-infra\"},\"spans\":[{\"traceId\":\"$trace_id\",\"spanId\":\"$span_id\",\"name\":\"infra-check\",\"kind\":1,\"startTimeUnixNano\":\"$now_ns\",\"endTimeUnixNano\":\"$now_ns\"}]}]}]}" > /dev/null; then
    trace_found() { grafana "/api/datasources/proxy/uid/tempo/api/traces/$trace_id" | grep -q "$span_id\|infra-check"; }
    if eventually 60 trace_found; then pass "a span sent to the collector is queryable in Tempo"; else fail "span $trace_id not found in Tempo"; fi
  else
    fail "collector rejected the OTLP trace"
  fi

  log_marker="logcheck$(random_secret | head -c 12)"
  if otlp logs "{\"resourceLogs\":[{\"resource\":$resource,\"scopeLogs\":[{\"scope\":{\"name\":\"check-infra\"},\"logRecords\":[{\"timeUnixNano\":\"$now_ns\",\"severityText\":\"INFO\",\"body\":{\"stringValue\":\"$log_marker\"},\"traceId\":\"$trace_id\",\"spanId\":\"$span_id\"}]}]}]}" > /dev/null; then
    query="%7Bservice_name%3D%22infra-check%22%7D%20%7C%3D%20%22$log_marker%22"
    log_found() { grafana "/api/datasources/proxy/uid/loki/loki/api/v1/query_range?query=$query&limit=5&since=10m" | grep -q "$log_marker"; }
    if eventually 60 log_found; then pass "a log record sent to the collector is queryable in Loki"; else fail "log $log_marker not found in Loki"; fi
  else
    fail "collector rejected the OTLP log"
  fi

  if otlp metrics "{\"resourceMetrics\":[{\"resource\":$resource,\"scopeMetrics\":[{\"scope\":{\"name\":\"check-infra\"},\"metrics\":[{\"name\":\"infra_check_probe\",\"gauge\":{\"dataPoints\":[{\"asInt\":\"1\",\"timeUnixNano\":\"$now_ns\"}]}}]}]}]}" > /dev/null; then
    metric_found() { prom_query 'infra_check_probe' | grep -q '"infra_check_probe"'; }
    if eventually 60 metric_found; then pass "a metric sent to the collector is queryable in Prometheus"; else fail "infra_check_probe not found in Prometheus"; fi
  else
    fail "collector rejected the OTLP metric"
  fi
else
  echo "-- observability: skipped (start with scripts/dev-up.sh --obs to include it)"
fi

# --- Services end to end through the gateway (only when the apps profile is running) -----------
if container_running api-gateway; then
  echo "-- services"
  for svc in identity-service catalog-service order-service inventory-service payment-sandbox payment-service api-gateway; do
    check "$svc is healthy" container_healthy "$svc"
  done

  gw=http://127.0.0.1:8080
  json_field() { sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p"; }
  http_status() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@"; }
  post_json() { curl -s --max-time 10 -H 'Content-Type: application/json' -H 'X-Token-Delivery: body' -d "$2" "$gw$1"; }

  email="check-infra-$(random_secret | head -c 10)@example.com"
  password="check-infra-$(random_secret | head -c 24)"
  code=$(http_status -H 'Content-Type: application/json' \
    -d "{\"email\":\"$email\",\"password\":\"$password\",\"displayName\":\"Infra Check\"}" "$gw/api/v1/auth/register")
  [[ "$code" == 201 ]] && pass "a customer can register through the gateway" || fail "register returned $code"

  customer=$(post_json /api/v1/auth/login "{\"email\":\"$email\",\"password\":\"$password\"}" | json_field accessToken)
  [[ -n "$customer" ]] && pass "the customer can log in and gets an access token" || fail "customer login failed"
  code=$(http_status -H "Authorization: Bearer $customer" "$gw/api/v1/users/me")
  [[ "$code" == 200 ]] && pass "the token works on a protected route" || fail "/users/me returned $code"

  admin=$(post_json /api/v1/auth/login \
    "{\"email\":\"$(env_value BOOTSTRAP_ADMIN_EMAIL)\",\"password\":\"$(env_value BOOTSTRAP_ADMIN_PASSWORD)\"}" | json_field accessToken)
  [[ -n "$admin" ]] && pass "the bootstrap admin can log in" || fail "admin login failed"

  word="infracheck$(random_secret | head -c 8)"
  sku="CHECK-$(random_secret | head -c 8 | tr '[:lower:]' '[:upper:]')"
  created=$(curl -s --max-time 10 -H "Authorization: Bearer $admin" -H 'Content-Type: application/json' \
    -d "{\"sku\":\"$sku\",\"name\":\"$word lamp\",\"category\":\"infra-check\",\"priceMinor\":1999,\"currency\":\"USD\"}" \
    "$gw/api/v1/admin/products")
  product_id=$(json_field id <<< "$created")
  [[ -n "$product_id" ]] && pass "an admin can create a product" || fail "product creation failed: $created"

  code=$(http_status "$gw/api/v1/products/$product_id")
  [[ "$code" == 200 ]] && pass "anyone can read the product" || fail "public product read returned $code"
  curl -s --max-time 10 "$gw/api/v1/products?q=$word" | grep -q "$product_id" &&
    pass "full-text search finds it" || fail "search for $word did not return the product"

  code=$(http_status -X POST -H "Authorization: Bearer $customer" -H 'Content-Type: application/json' -d '{}' "$gw/api/v1/admin/products")
  [[ "$code" == 403 ]] && pass "a customer is refused on admin routes" || fail "customer on admin route got $code"
  code=$(http_status "$gw/internal/v1/prices?ids=$product_id")
  [[ "$code" == 401 ]] && pass "internal endpoints are not reachable through the gateway" || fail "/internal returned $code"
  code=$(http_status -H "Authorization: Bearer $admin" "$gw/actuator/prometheus")
  [[ "$code" == 404 ]] && pass "service actuators are not routed, even for an admin" || fail "/actuator/prometheus returned $code"

  # Milestone 4: stock, cart and an idempotent order, all through the gateway.
  stocked=$(curl -s --max-time 10 -H "Authorization: Bearer $admin" -H 'Content-Type: application/json' \
    -d '{"delta":5,"reason":"RESTOCK"}' "$gw/api/v1/admin/inventory/$product_id/adjustments")
  [[ "$stocked" == *'"available":5'* ]] && pass "an admin can restock a product" || fail "restock failed: $stocked"
  curl -s --max-time 10 "$gw/api/v1/inventory/$product_id" | grep -q '"inStock":true' &&
    pass "anyone can see it is in stock" || fail "public availability is wrong"

  code=$(http_status -X PUT -H "Authorization: Bearer $customer" -H 'Content-Type: application/json' \
    -d '{"quantity":2}' "$gw/api/v1/cart/items/$product_id")
  [[ "$code" == 200 ]] && pass "the customer can fill a cart" || fail "cart update returned $code"

  key="check-infra-$(random_secret | head -c 20)"
  order_body="{\"items\":[{\"productId\":\"$product_id\",\"quantity\":2}],\"paymentToken\":\"tok_success\"}"
  # Body and status from one call: the status is appended on its own last line. (No temp file:
  # on Windows, curl.exe and bash do not agree on where /tmp is.)
  place_order() {
    curl -s --max-time 10 -w '\n%{http_code}' -H "Authorization: Bearer $customer" \
      -H "Idempotency-Key: $key" -H 'Content-Type: application/json' -d "$order_body" "$gw/api/v1/orders"
  }
  first=$(place_order)
  first_code=$(tail -n 1 <<< "$first")
  order_id=$(head -n -1 <<< "$first" | json_field id)
  [[ "$first_code" == 202 && -n "$order_id" && "$first" == *'"totalMinor":3998'* ]] &&
    pass "an order is accepted and priced by catalog (2 x 1999)" || fail "order returned $first_code: $first"
  replay=$(place_order)
  replay_id=$(head -n -1 <<< "$replay" | json_field id)
  # Both ids must be real: two empty strings are equal too.
  [[ "$(tail -n 1 <<< "$replay")" == 200 && -n "$order_id" && "$replay_id" == "$order_id" ]] &&
    pass "retrying with the same Idempotency-Key returns the same order" || fail "replay returned: $replay"

  # Milestones 5 and 6: the checkout saga, over the outbox and Kafka. Inventory reserves, payment
  # charges the sandbox, inventory commits, and the order is confirmed.
  order_status() {
    curl -s --max-time 10 -H "Authorization: Bearer $customer" "$gw/api/v1/orders/$1" | sed -n 's/.*"status":"\([A-Z_]*\)".*/\1/p'
  }
  order_is() { [[ "$(order_status "$1")" == "$2" ]]; }
  available() { curl -s --max-time 10 "$gw/api/v1/inventory/$product_id" | grep -q "\"available\":$1"; }
  payments_for() { curl -s --max-time 10 -H "Authorization: Bearer $admin" "$gw/api/v1/admin/payments?orderId=$1"; }
  # Places an order for this product and prints its id.
  order_with() {
    curl -s --max-time 10 -H "Authorization: Bearer $customer" -H "Idempotency-Key: check-infra-$(random_secret | head -c 20)" \
      -H 'Content-Type: application/json' \
      -d "{\"items\":[{\"productId\":\"$product_id\",\"quantity\":$2}],\"paymentToken\":\"$1\"}" \
      "$gw/api/v1/orders" | json_field id
  }

  if eventually 60 order_is "$order_id" CONFIRMED; then pass "the saga reserved, charged and committed: the order is CONFIRMED"
  else fail "order $order_id is $(order_status "$order_id"), expected CONFIRMED"; fi
  available 3 && pass "the confirmed order holds 2 of the 5 units" || fail "availability after confirmation is wrong"
  paid=$(payments_for "$order_id")
  # INV-3: one attempt for the order, and it succeeded.
  [[ $(grep -o '"status"' <<< "$paid" | wc -l) -eq 1 && "$paid" == *'"status":"SUCCEEDED"'* && "$paid" == *'"amountMinor":3998'* ]] &&
    pass "payment-service holds exactly one successful charge for it" || fail "payment attempts for $order_id: $paid"

  declined=$(order_with tok_decline 1)
  declined_failed() {
    curl -s --max-time 10 -H "Authorization: Bearer $customer" "$gw/api/v1/orders/$declined" | grep -q '"failureReason":"PAYMENT_DECLINED"'
  }
  if [[ -n "$declined" ]] && eventually 60 declined_failed; then pass "a declined card fails the order as PAYMENT_DECLINED"
  else fail "the declined order ($declined) is $(order_status "$declined")"; fi
  # INV-5: the failed order holds no stock and no money.
  available 3 && pass "its reserved unit was released again" || fail "a declined order kept its stock"
  [[ "$(payments_for "$declined")" == *'"status":"FAILED"'* ]] &&
    pass "its payment attempt is FAILED, with nothing charged" || fail "declined payment: $(payments_for "$declined")"

  too_many=$(order_with tok_success 50)
  out_of_stock() {
    curl -s --max-time 10 -H "Authorization: Bearer $customer" "$gw/api/v1/orders/$too_many" | grep -q '"failureReason":"OUT_OF_STOCK"'
  }
  if [[ -n "$too_many" ]] && eventually 30 out_of_stock; then pass "an order for more than is in stock fails as OUT_OF_STOCK"
  else fail "the oversized order ($too_many) did not fail as OUT_OF_STOCK"; fi
  available 3 && pass "the rejected order took no stock" || fail "a rejected order changed availability"
  [[ "$(payments_for "$too_many")" == *'"items":[]'* ]] &&
    pass "and payment was never asked to charge for it" || fail "payment attempts for a rejected order: $(payments_for "$too_many")"

  # A charge whose answer never arrives is UNKNOWN, not declined: the stock stays held for it.
  timed_out=$(order_with tok_timeout 1)
  unknown() { [[ "$(payments_for "$timed_out")" == *'"status":"UNKNOWN"'* ]]; }
  if [[ -n "$timed_out" ]] && eventually 60 unknown; then pass "a provider timeout leaves the payment UNKNOWN"
  else fail "the timed-out order's payment: $(payments_for "$timed_out")"; fi
  order_is "$timed_out" PAYMENT_PROCESSING && available 2 &&
    pass "and the order waits for reconciliation instead of failing (stock still held)" ||
    fail "the timed-out order is $(order_status "$timed_out")"
  # Milestone 7: the reconciler asks the provider, finds the charge, and the order completes.
  if eventually 120 order_is "$timed_out" CONFIRMED; then pass "the reconciler found the charge: the order is CONFIRMED"
  else fail "the timed-out order is $(order_status "$timed_out") after reconciliation"; fi
  [[ "$(payments_for "$timed_out")" == *'"status":"SUCCEEDED"'* ]] &&
    pass "its payment was settled from the provider's record, not charged again" ||
    fail "reconciled payment: $(payments_for "$timed_out")"
  code=$(http_status -H "Authorization: Bearer $admin" "$gw/api/v1/admin/sagas")
  [[ "$code" == 200 ]] && pass "an admin can list sagas" || fail "/admin/sagas returned $code"
  code=$(http_status -H "Authorization: Bearer $customer" "$gw/api/v1/admin/payments")
  [[ "$code" == 403 ]] && pass "a customer cannot see payments" || fail "customer on /admin/payments got $code"
  other_email="check-infra-$(random_secret | head -c 10)@example.com"
  http_status -H 'Content-Type: application/json' \
    -d "{\"email\":\"$other_email\",\"password\":\"$password\",\"displayName\":\"Other\"}" "$gw/api/v1/auth/register" > /dev/null
  stranger=$(post_json /api/v1/auth/login "{\"email\":\"$other_email\",\"password\":\"$password\"}" | json_field accessToken)
  code=$(http_status -H "Authorization: Bearer $stranger" "$gw/api/v1/orders/$order_id")
  [[ "$code" == 404 ]] && pass "another customer cannot see it (404, not 403)" || fail "another customer got $code"

  # Leave the shared catalogue as we found it.
  http_status -X POST -H "Authorization: Bearer $admin" "$gw/api/v1/admin/products/$product_id/archive" > /dev/null

  if container_running prometheus; then
    # Counted per service, not per series: right after a restart Prometheus still holds the old
    # containers' series for a few minutes, and a raw count would be too high.
    services_up() {
      [[ $(curl -sS --get --data-urlencode 'query=count(count by (service) (up{job="services"} == 1))' http://127.0.0.1:9090/api/v1/query |
        grep -oE '"[0-9]+"\]' | tr -dc '0-9') == 6 ]]
    }
    if eventually 60 services_up; then pass "prometheus scrapes all six services"; else fail "prometheus is not scraping all six services"; fi
    outbox_metrics() {
      [[ $(curl -sS --get --data-urlencode 'query=count(count by (service) (outbox_pending))' http://127.0.0.1:9090/api/v1/query |
        grep -oE '"[0-9]+"\]' | tr -dc '0-9') == 3 ]]
    }
    if eventually 60 outbox_metrics; then pass "all three publishing services report their outbox backlog"; else fail "outbox_pending is missing for order, inventory or payment"; fi
    check "the outbox, dead-letter and consumer-lag alert rules are loaded" bash -c \
      "curl -sS http://127.0.0.1:9090/api/v1/rules | grep -q OutboxBacklogStuck && curl -sS http://127.0.0.1:9090/api/v1/rules | grep -q DeadLetterMessages"
  fi
else
  echo "-- services: skipped (start with scripts/dev-up.sh --apps to include them)"
fi

echo
echo "$passed passed, $failed failed"
((failed == 0))
