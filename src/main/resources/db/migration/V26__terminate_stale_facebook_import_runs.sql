UPDATE facebook_import_run
   SET status = 'TERMINATED',
       finished_at = now(),
       phase = 'Terminated',
       status_detail = 'Timed out after PT1H before timeout enforcement was deployed',
       phase_index = 8,
       phase_count = 8,
       last_status_at = now()
 WHERE status = 'RUNNING'
   AND finished_at IS NULL
   AND started_at <= now() - INTERVAL '1 hour';
