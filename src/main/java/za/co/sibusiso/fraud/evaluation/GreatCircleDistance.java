package za.co.sibusiso.fraud.evaluation;

/** Spherical great-circle distance. Constants define units/geometry, never fraud policy. */
public final class GreatCircleDistance {
    private static final double MEAN_EARTH_RADIUS_KM = 6371.0088;
    private GreatCircleDistance() { }

    public static double kilometres(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latA = StrictMath.toRadians(latitudeA), latB = StrictMath.toRadians(latitudeB);
        double halfLatitude = StrictMath.sin((latB - latA) / 2);
        double halfLongitude = StrictMath.sin(StrictMath.toRadians(longitudeB - longitudeA) / 2);
        double haversine = halfLatitude * halfLatitude
                + StrictMath.cos(latA) * StrictMath.cos(latB) * halfLongitude * halfLongitude;
        haversine = StrictMath.max(0, StrictMath.min(1, haversine));
        return 2 * MEAN_EARTH_RADIUS_KM * StrictMath.atan2(StrictMath.sqrt(haversine), StrictMath.sqrt(1 - haversine));
    }
}
