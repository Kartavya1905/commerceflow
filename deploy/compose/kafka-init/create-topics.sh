#!/bin/sh
# Reconciles Kafka topics with contracts/src/main/resources/events/topics.json.
#
# - Missing topics (and their .dlt companions) are created with the catalogue's partition count
#   and retention.
# - Existing topics get their retention corrected.
# - A partition-count mismatch is an error, never "fixed" automatically: changing the count
#   remaps keys to partitions and breaks per-order ordering (ARCHITECTURE.md F.5).
set -eu

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:29092}"
CATALOG="${TOPIC_CATALOG:-/config/topics.json}"
BIN=/opt/kafka/bin
DAY_MS=86400000
export KAFKA_HEAP_OPTS="-Xmx256m"

[ -r "$CATALOG" ] || { echo "kafka-init: cannot read $CATALOG" >&2; exit 1; }

# One line per topic: "<name> <partitions> <retention.ms>", DLTs included.
desired=$(jq -r --argjson day "$DAY_MS" '
  .deadLetter as $dlt
  | .topics[]
  | "\(.name) \(.partitions) \(.retentionDays * $day)",
    "\(.name)\($dlt.suffix) \(.partitions) \($dlt.retentionDays * $day)"
' "$CATALOG")

# One describe call for the whole cluster; summary lines carry PartitionCount and Configs.
current=$("$BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --describe --exclude-internal)

# The loop runs in a subshell (it is on the right of a pipe), so failures are signalled with a
# marker file rather than a variable.
rm -f /tmp/kafka-init-failed
echo "$desired" | while read -r name partitions retention; do
  summary=$(echo "$current" | awk -v t="$name" '$1 == "Topic:" && $2 == t && /PartitionCount:/')

  if [ -z "$summary" ]; then
    "$BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
      --topic "$name" --partitions "$partitions" --replication-factor 1 \
      --config "retention.ms=$retention" --config min.insync.replicas=1 > /dev/null
    echo "kafka-init: created $name (partitions=$partitions, retention.ms=$retention)"
    continue
  fi

  have_partitions=$(echo "$summary" | sed -n 's/.*PartitionCount: *\([0-9]*\).*/\1/p')
  have_retention=$(echo "$summary" | sed -n 's/.*retention\.ms=\([0-9]*\).*/\1/p')

  if [ "$have_partitions" != "$partitions" ]; then
    echo "kafka-init: ERROR $name has $have_partitions partitions, catalogue says $partitions." >&2
    echo "kafka-init:       Not changing it automatically (it would reorder keys). Recreate the" >&2
    echo "kafka-init:       local cluster with 'scripts/dev-down.sh --volumes' if this is intended." >&2
    touch /tmp/kafka-init-failed
    continue
  fi

  if [ "$have_retention" != "$retention" ]; then
    "$BIN/kafka-configs.sh" --bootstrap-server "$BOOTSTRAP" --alter --entity-type topics \
      --entity-name "$name" --add-config "retention.ms=$retention" > /dev/null
    echo "kafka-init: updated $name retention.ms $have_retention -> $retention"
  else
    echo "kafka-init: $name ok"
  fi
done

# Topics on the broker that the catalogue does not know about: report, never delete.
known=$(echo "$desired" | awk '{print $1}')
echo "$current" | awk '$1 == "Topic:" && /PartitionCount:/ {print $2}' | while read -r t; do
  echo "$known" | grep -qxF "$t" || echo "kafka-init: WARNING topic $t is not in the catalogue"
done

if [ -e /tmp/kafka-init-failed ]; then
  exit 1
fi
echo "kafka-init: done"
