-- online-shop main added IdentityPersistenceTest (Testcontainers). Keep backend-unit Docker-free by excluding it,
-- like the other Testcontainers classes. Found by the first real EAHP run: without Docker it errored instead of running.
UPDATE qa_test_suites
SET command = '["mvn","-B","-ntp","verify","-DskipTests=false","-Dtest=!CatalogApiTest,!PostgresPersistenceTest,!OrderApiTest,!IdentityPersistenceTest","-Dsurefire.failIfNoSpecifiedTests=false"]',
    description = 'All backend modules except the Testcontainers suites (Catalog API, Order persistence/API, Identity persistence).'
WHERE project_key = 'online-shop' AND key = 'backend-unit';
