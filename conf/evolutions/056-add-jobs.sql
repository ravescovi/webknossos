-- https://github.com/scalableminds/webknossos/pull/4826

START TRANSACTION;

CREATE TABLE webknossos.jobs(
  _id CHAR(36) PRIMARY KEY DEFAULT '',
  _owner CHAR(24) NOT NULL,
  created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  isDeleted BOOLEAN NOT NULL DEFAULT false
);


CREATE VIEW webknossos.jobs_ AS SELECT * FROM webknossos.jobs WHERE NOT isDeleted;

UPDATE webknossos.releaseInformation SET schemaVersion = 56;

COMMIT TRANSACTION;
