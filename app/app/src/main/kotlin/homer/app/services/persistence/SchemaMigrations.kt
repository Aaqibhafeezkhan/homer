package homer.app.services.persistence

import java.sql.Statement

// The in-place schema upgrades [DatabaseService.migrate] runs after `SchemaUtils.create`, one
// function per span of versions. `SchemaUtils.create` adds missing tables but never ALTERs an
// existing one, so each step adds its columns and indexes by hand, guarded on the version it
// upgrades from. A new version adds its step here and its row to the schema-version table in
// `services/persistence/CLAUDE.md`.

/** Runs every schema step a database at [current] still needs, in version order. */
internal fun upgradeSchema(
    st: Statement,
    current: Int,
) {
    migrateToV10(st, current)
    migrateToV15(st, current)
    migrateToV20(st, current)
    migrateToV24(st, current)
    migrateToV27(st, current)
    migrateToV28(st, current)
    migrateToV30(st, current)
}

/** The column names of [table], from `PRAGMA table_info`. */
private fun columnNames(
    st: Statement,
    table: String,
): Set<String> =
    st.executeQuery("PRAGMA table_info($table)").use { rs ->
        generateSequence { if (rs.next()) rs.getString("name") else null }.toSet()
    }

/**
 * Adds [column] to [table] unless it already has it — SchemaUtils.create never ALTERs an
 * existing table. Returns whether the column was added, for a step that backfills it.
 */
private fun addColumnIfMissing(
    st: Statement,
    table: String,
    column: String,
    definition: String,
): Boolean {
    if (column in columnNames(st, table)) return false
    st.execute("ALTER TABLE $table ADD COLUMN $column $definition")
    return true
}

/** Schema steps v1→v10 of [DatabaseService.migrate]; each step is guarded on the version it upgrades from. */
private fun migrateToV10(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 2) {
        // v1 had questions without dispatch_bound and no dispatches table.
        // SchemaUtils.create above adds the dispatches table (create-if-missing)
        // but never ALTERs an existing table, so add the column by hand.
        addColumnIfMissing(st, "questions", "dispatch_bound", "INTEGER NOT NULL DEFAULT 0")
    }
    // v2→v3 adds the continuations table; v3→v4 adds the result_tokens table.
    // SchemaUtils.create above already created both (create-if-missing), so each
    // step is just the version stamp.
    if (current in 3 until 5) {
        // v4→v5 adds the continuation_watches table (created above) and the
        // continuations.kind column. SchemaUtils.create never ALTERs an existing
        // table, so add the column by hand on a dev DB that already had v3/v4
        // continuations. The join feature is unreleased, so this only ever runs
        // against local dev databases.
        addColumnIfMissing(st, "continuations", "kind", "TEXT NOT NULL DEFAULT 'SINGLE'")
    }
    // v5→v6 adds the cost_entries table; SchemaUtils.create above already
    // created it (create-if-missing), so the step is just the version stamp.
    if (current in 3 until 7) {
        // v6→v7 adds result_tokens.consumer_agent (audience binding, ADR-0025).
        // SchemaUtils.create never ALTERs an existing table, so add the column by
        // hand on a dev DB that already had a v4+ result_tokens table. The empty
        // default makes any pre-existing (ephemeral, TTL'd) token match no agent,
        // so it fails closed until swept. The feature is unreleased, so this only
        // ever runs against local dev databases.
        addColumnIfMissing(st, "result_tokens", "consumer_agent", "TEXT NOT NULL DEFAULT ''")
    }
    if (current in 1 until 8) {
        // v7→v8 adds process/dispatch/continuation owner columns (ADR-0038).
        // Lower bound is 1 (not 3, unlike the sibling blocks above): processes
        // has existed since v1, so a DB frozen at v1/v2 already has a
        // processes table SchemaUtils.create won't ALTER.
        for (t in listOf("processes", "dispatches", "continuations")) {
            addColumnIfMissing(st, t, "owner", "TEXT")
        }
    }
    // v8→v9 adds the server_meta table; SchemaUtils.create above already
    // created it (create-if-missing), so the step is just the version stamp.
    if (current in 3 until 10) {
        // v9→v10 adds continuations.fire_attempts / next_attempt_at (transient-
        // failure retry). SchemaUtils.create never ALTERs an existing table.
        // Existing rows default to 0 attempts and no scheduled retry, which is
        // exactly the pre-upgrade behaviour.
        addColumnIfMissing(st, "continuations", "fire_attempts", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(st, "continuations", "next_attempt_at", "INTEGER")
    }
}

/** Schema steps v10→v15 of [DatabaseService.migrate]; each step is guarded on the version it upgrades from. */
private fun migrateToV15(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 11) {
        // v10→v11 adds processes.trace_id (Langfuse deep link). Lower bound 1:
        // processes has existed since v1, and SchemaUtils.create never ALTERs.
        addColumnIfMissing(st, "processes", "trace_id", "VARCHAR(32)")
    }
    if (current in 1 until 12) {
        // v11→v12 adds processes.record_purge_at. Existing rows keep NULL, which
        // the sweep reads as "same as purge_at" — the pre-upgrade behaviour.
        addColumnIfMissing(st, "processes", "record_purge_at", "INTEGER")
    }
    if (current in 1 until 13) {
        // v12→v13 adds processes.created_at, the cooldown's authoritative
        // "last run started", backfilled from the history entry it used to be
        // read from so the bound keeps holding across the upgrade.
        if (addColumnIfMissing(st, "processes", "created_at", "INTEGER")) {
            // Backfill from the first history entry, which is where the start
            // time used to be read from. Without this the cooldown would not
            // bind for one run per agent after the upgrade — precisely on the
            // deployments that configured it. Rows with an empty history keep
            // NULL (json_extract returns NULL), which reads as "never ran".
            st.execute(
                "UPDATE processes SET created_at = json_extract(history, '\$[0][0]') " +
                    "WHERE created_at IS NULL",
            )
        }
    }
    if (current in 1 until 14) {
        // v13→v14 adds processes.ended_at, the exact terminal time the
        // `cooldown.afterFailure` window is measured from, backfilled from the
        // last history entry of rows that are already terminal.
        if (addColumnIfMissing(st, "processes", "ended_at", "INTEGER")) {
            // Same reasoning as created_at: backfill the terminal time from the
            // last history entry, for terminal rows only. An active row has no
            // terminal time yet and must stay NULL.
            st.execute(
                "UPDATE processes SET ended_at = json_extract(history, '\$[#-1][0]') " +
                    "WHERE ended_at IS NULL " +
                    "AND status IN ('FINISHED', 'FAILED', 'KILLED', 'UNKNOWN')",
            )
        }
    }
    if (current in 1 until 15) {
        // v14→v15 adds processes.secret_input_keys, the redaction metadata
        // captured at run time. Existing rows keep NULL and fall back to the
        // current agent definition — exactly the behaviour they had before,
        // rather than a sudden blanket redaction of every retained record.
        addColumnIfMissing(st, "processes", "secret_input_keys", "TEXT")
    }
}

/** Schema steps v15→v20 of [DatabaseService.migrate]; each step is guarded on the version it upgrades from. */
private fun migrateToV20(
    st: Statement,
    current: Int,
) {
    if (current in 3 until 16) {
        // v15→v16 adds secret_input_keys to dispatches and continuations —
        // the target agent's secret flags captured at registration, so a
        // definition edited before the row fires cannot expose a stored value.
        // Lower bound 3: both tables arrive complete from SchemaUtils.create
        // for anything older, and create never ALTERs. Existing rows keep NULL,
        // which falls back to the flags in force at fire time.
        for (t in listOf("dispatches", "continuations")) {
            addColumnIfMissing(st, t, "secret_input_keys", "TEXT")
        }
    }
    if (current in 3 until 17) {
        // v16→v17 adds secret_param_names to dispatches and continuations —
        // the target agent's secret paramNames captured at registration.
        // `dispatches.params`/`continuations.params` are stored keyed by
        // paramName, so secret_input_keys (envName-keyed) alone cannot survive
        // the input being deleted or re-pointed to a different envName since
        // registration (a security follow-up; see ADR-0052). Lower bound 3,
        // same reasoning as the v16 block above. Existing rows keep NULL, which
        // falls back to the envName-only check redactStoredParams already had.
        for (t in listOf("dispatches", "continuations")) {
            addColumnIfMissing(st, t, "secret_param_names", "TEXT")
        }
    }
    if (current in 1 until 18) {
        // v17→v18 adds the retain-block columns (ADR-0053): the globs
        // captured at terminal status, the workspace-sweep marker for
        // retained rows, and the cap-truncation flag. Existing rows keep
        // NULL = nothing retained, exactly the pre-upgrade behaviour.
        addColumnIfMissing(st, "processes", "retain_artifacts", "TEXT")
        addColumnIfMissing(st, "processes", "workspace_purged_at", "INTEGER")
        addColumnIfMissing(st, "processes", "retained_truncated", "INTEGER")
    }
    if (current in 1 until 19) {
        // v18→v19 adds processes.parent_process_id (ADR-0058). Existing rows
        // keep NULL = no known parent, exactly the pre-upgrade behaviour.
        addColumnIfMissing(st, "processes", "parent_process_id", "INTEGER")
    }
    // v19→v20 adds the process_tags table (ADR-0060). SchemaUtils.create
    // above already created it (create-if-missing), so the step is just the
    // version stamp — existing runs simply have no tag rows.
}

/** Schema steps v20→v24 of [DatabaseService.migrate]; each step is guarded on the version it upgrades from. */
private fun migrateToV24(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 21) {
        // v20→v21 adds processes.cost_source (ADR-0061). Existing rows were
        // priced from the CLI figure, so 'cli' is the truthful default.
        addColumnIfMissing(st, "processes", "cost_source", "TEXT NOT NULL DEFAULT 'cli'")
    }
    if (current in 1 until 22) {
        // v21→v22 adds processes.root_process_id (ADR-0058). Existing rows stay
        // NULL — no backfill; NULL reads as "flow root unknown" (ADR-0058 amendment).
        addColumnIfMissing(st, "processes", "root_process_id", "INTEGER")
    }
    if (current in 1 until 23) {
        // v22→v23 indexes the process-list filters; SchemaUtils.create only indexes a
        // table it creates. Names match the Processes table declaration.
        st.execute("CREATE INDEX IF NOT EXISTS processes_agent_name ON processes (agent_name)")
        st.execute("CREATE INDEX IF NOT EXISTS processes_status ON processes (status)")
        st.execute("CREATE INDEX IF NOT EXISTS processes_parent_process_id ON processes (parent_process_id)")
        st.execute("CREATE INDEX IF NOT EXISTS processes_root_process_id ON processes (root_process_id)")
        st.execute("CREATE INDEX IF NOT EXISTS processes_owner ON processes (owner)")
    }
    if (current in 1 until 24) {
        // v23→v24 indexes the sweep, cooldown and lookup filters that scanned their
        // tables. Names and definitions match the table declarations in Tables.kt.
        st.execute("CREATE INDEX IF NOT EXISTS processes_artifacts_dir ON processes (artifacts_dir)")
        st.execute(
            "CREATE INDEX IF NOT EXISTS processes_workspace_purge ON processes (status, purge_at) " +
                "WHERE (artifacts_dir IS NOT NULL) AND (workspace_purged_at IS NULL)",
        )
        st.execute("CREATE INDEX IF NOT EXISTS processes_record_purge ON processes (status, record_purge_at)")
        st.execute(
            "CREATE INDEX IF NOT EXISTS processes_legacy_record_purge ON processes (status, purge_at) " +
                "WHERE record_purge_at IS NULL",
        )
        st.execute(
            "CREATE INDEX IF NOT EXISTS processes_agent_status_ended ON processes (agent_name, status, ended_at)",
        )
        st.execute(
            "CREATE INDEX IF NOT EXISTS questions_status_dispatch_bound ON questions (status, dispatch_bound, process_id)",
        )
        st.execute(
            "CREATE INDEX IF NOT EXISTS questions_process_dispatch_bound ON questions (process_id, dispatch_bound)",
        )
        st.execute("CREATE INDEX IF NOT EXISTS dispatches_status_expires ON dispatches (status, expires_at)")
        st.execute(
            "CREATE INDEX IF NOT EXISTS continuations_watched_status ON continuations (watched_process_id, status)",
        )
        st.execute("CREATE INDEX IF NOT EXISTS continuations_status_expires ON continuations (status, expires_at)")
    }
}

/** Schema steps v24→v27 of [DatabaseService.migrate]; each step is guarded on the version it upgrades from. */
private fun migrateToV27(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 25) {
        // v24→v25 moves write-once input_params into the process_inputs
        // side table (created above). See ADR-0020.
        // Guarded on the column: on a database that already migrated (fresh
        // installs at v25 included, where SchemaUtils.create declared no such
        // column) this must not attempt a second backfill or a second DROP.
        if ("input_params" in columnNames(st, "processes")) {
            // Copied before the drop, in this same transaction, so no committed
            // state ever has the column gone and the value nowhere. COALESCE:
            // the column was NOT NULL, but a hand-edited or pre-v1 row could
            // still read NULL, and the side table's column is NOT NULL.
            st.execute(
                "INSERT INTO process_inputs (process_id, input_params) " +
                    "SELECT id, COALESCE(input_params, '{}') FROM processes",
            )
            st.execute("ALTER TABLE processes DROP COLUMN input_params")
        }
    }
    if (current in 1 until 26) {
        // v25→v26 indexes the settled-row prune and the paginated list filters of
        // dispatches and continuations. Names and definitions match Tables.kt.
        st.execute("CREATE INDEX IF NOT EXISTS dispatches_status_created ON dispatches (status, created_at)")
        st.execute("CREATE INDEX IF NOT EXISTS dispatches_agent_name ON dispatches (agent_name)")
        st.execute("CREATE INDEX IF NOT EXISTS dispatches_owner ON dispatches (owner)")
        st.execute("CREATE INDEX IF NOT EXISTS continuations_status_created ON continuations (status, created_at)")
        st.execute("CREATE INDEX IF NOT EXISTS continuations_agent_name ON continuations (agent_name)")
        st.execute("CREATE INDEX IF NOT EXISTS continuations_owner ON continuations (owner)")
    }
    if (current in 1 until 27) {
        // v26→v27 adds continuations.thread_id, backfilled from params, and its index.
        // Definitions match Tables.kt. json_valid: a hand-edited row whose
        // params are not JSON keeps NULL instead of failing the upgrade.
        addColumnIfMissing(st, "continuations", "thread_id", "TEXT")
        st.execute(
            "UPDATE continuations SET thread_id = json_extract(params, '\$.HMR_LG_THREAD') " +
                "WHERE thread_id IS NULL AND json_valid(params)",
        )
        st.execute("CREATE INDEX IF NOT EXISTS continuations_thread_id ON continuations (thread_id)")
    }
}

/** v27→v28 indexes the read-grant arm of the paged question list. The name matches Tables.kt. */
private fun migrateToV28(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 28) {
        st.execute("CREATE INDEX IF NOT EXISTS questions_agent_name ON questions (agent_name)")
    }
}

/**
 * v29→v30 adds the stored pre-start failure reason and the run it names. Its own version, not
 * main's v29 (the `run_messages` step): a database that release already stamped `user_version = 29`
 * skips every step guarded below 30 when it has no column to add, so folding both features into
 * v29 would leave such a database without the columns for good.
 */
private fun migrateToV30(
    st: Statement,
    current: Int,
) {
    if (current in 1 until 30) {
        // No backfill: a run that ended before this version was never observed by the code
        // that maps a cause to a reason, and a guessed reason is worse than none.
        addColumnIfMissing(st, "processes", "failure_reason", "TEXT")
        addColumnIfMissing(st, "processes", "failure_reason_run_id", "INTEGER")
    }
}
