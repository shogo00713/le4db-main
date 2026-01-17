package model;

/**
 * 特定の場所の近くの停留所
 * @stopId 停留所ID
 * @name 停留所名
 * @distance 距離(m)
 * @lat 緯度
 * @lon 経度
 */
public class NearByStops {
    public final int stopId;
    public final String name;
    public final int distance;
    public final double lat;
    public final double lon;

    public NearByStops(int stopId, String name, int distance, double lat, double lon) {
        this.stopId = stopId;
        this.name = name;
        this.distance = distance;
        this.lat = lat;
        this.lon = lon;
        }
    }