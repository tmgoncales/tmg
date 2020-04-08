CREATE TABLE "ANALYSIS_PROPERTIES" (
  "UUID" VARCHAR(40) NOT NULL,
  "SNAPSHOT_UUID" VARCHAR(40) NOT NULL,
  "KEE" VARCHAR(512) NOT NULL,
  "TEXT_VALUE" VARCHAR(4000),
  "CLOB_VALUE" CLOB,
  "IS_EMPTY" BOOLEAN NOT NULL,
  "CREATED_AT" BIGINT NOT NULL,

  CONSTRAINT "PK_ANALYSIS_PROPERTIES" PRIMARY KEY ("UUID")
);
CREATE INDEX "SNAPSHOT_UUID" ON "ANALYSIS_PROPERTIES" ("SNAPSHOT_UUID");
