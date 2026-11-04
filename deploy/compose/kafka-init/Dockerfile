# Kafka CLI tools plus a static jq binary, so topics can be created straight from topics.json.
FROM ghcr.io/jqlang/jq:1.8.1 AS jq

FROM apache/kafka:4.3.2
COPY --from=jq /jq /usr/local/bin/jq
COPY --chmod=0755 create-topics.sh /usr/local/bin/create-topics.sh
# The base image's unprivileged user, stated here so a change of base cannot make it root.
USER appuser
ENTRYPOINT ["/usr/local/bin/create-topics.sh"]
