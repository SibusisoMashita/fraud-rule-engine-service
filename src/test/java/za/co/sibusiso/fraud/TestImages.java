package za.co.sibusiso.fraud;

/** Release dependency images shared by real-dependency integration tests. */
public final class TestImages {
    public static final String POSTGRES =
            "postgres:17.11-alpine3.24@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24";
    public static final String REDIS =
            "redis:8.2.10-alpine3.22@sha256:b51665e66f00759be7c3152ad5ac3c66fb2f619c13ef62dea7cc1f9914524635";
    public static final String INITDB_ARGS = "--locale-provider=builtin --locale=C.UTF-8";

    private TestImages() {}
}
