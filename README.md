# Robot Test Generator

## Run modes

The default setting is interactive mode. Run it with:

```powershell
.\mvnw spring-boot:run
```

The existing interactive prompts remain in use while `sync.h2.db=false`.

To process active requests from a local H2 database, set `sync.h2.db=true` in
`src/main/resources/application.properties` and run the same command. Each
active row is processed in order: generate the Robot project, sync it to Git,
then publish evidence to Confluence. A row is changed to inactive only after
all three stages succeed. A failed row stays active for retry; processing
continues with the next active row. Successful rows will not run again unless
they are reactivated.

The default database URL is `jdbc:h2:file:./data/robot-generator`, relative to
the application's working directory. The schema is created automatically when
H2 batch mode runs. The H2 library version is managed by the existing Spring
Boot parent in `pom.xml`.

## H2 request schema

The application creates `ROBOT_GENERATION_REQUEST` with these columns:

| Column | Required | Meaning |
| --- | --- | --- |
| `ID` | Generated | Request identifier |
| `EIM_ID` | Yes | EIM/project repository identifier |
| `PROJECT_NAME` | Yes | Name used for generated project and Confluence page |
| `BASE_URL` | Yes | HTTP or HTTPS application URL |
| `ENDPOINT` | No | Request endpoint; defaults to `/` |
| `IS_ACTIVE` | No | Whether to process the row; defaults to `TRUE` |
| `GIT_REPOSITORY_URL` | No | HTTPS Git URL override; blank uses `git.repo.url/EIM_ID` when automatic EIM Git sync is enabled |
| `GIT_PROJECT_PATH` | No | Existing relative directory in the Git repository; defaults to `.` |
| `GIT_SYNC_MODE` | No | `OVERWRITE` or `MERGE`; defaults to `OVERWRITE` |
| `JENKINS_BUILD_URL` | Yes | Completed Jenkins build URL, or an archived `/artifact/.../log.html` URL supported by the configured evidence source |

Example insert, after running the application once with `sync.h2.db=true` to
create the table:

```sql
INSERT INTO ROBOT_GENERATION_REQUEST
    (EIM_ID, PROJECT_NAME, BASE_URL, ENDPOINT, IS_ACTIVE,
     GIT_REPOSITORY_URL, GIT_PROJECT_PATH, GIT_SYNC_MODE, JENKINS_BUILD_URL)
VALUES
    ('EIM-42', 'Portal Health Check', 'https://portal.example.com', '/health', TRUE,
     NULL, '.', 'OVERWRITE', 'https://jenkins.example.com/job/portal/42/');
```

When `GIT_REPOSITORY_URL` is `NULL`, the configured `git.repo.url` is used as
the repository base and `EIM_ID` is appended. The configured Git URL must be
HTTPS because evidence publication validates the Git origin. `GIT_PROJECT_PATH`
must already exist in the cloned repository; use `.` for its root. Jenkins must
have completed the build and be configured to expose the Robot log artifact.
Confluence and Jenkins credentials and URLs continue to come from the existing
application properties/environment variables.

## Use with DBeaver

1. Run H2 batch mode once so the application creates the database and table,
   then stop the application before opening the file in DBeaver.
2. In DBeaver choose **New Database Connection**, select **H2**, and let it
   download the H2 driver if prompted.
3. Choose the embedded/file connection and set the JDBC URL to the absolute
   path of the database file, for example:
   `jdbc:h2:file:C:/Projects/robot-test-generator/data/robot-generator`.
   Do not add the `.mv.db` suffix.
4. Use user `sa` and leave the password empty unless you changed the H2
   properties. Test the connection and finish.
5. Open an SQL Editor, run the insert above, and commit the transaction. Set
   `IS_ACTIVE=TRUE` for requests to be picked up on the next application run.

The local database directory is ignored by Git. Keep the application stopped
while DBeaver is connected to the embedded database file.