package model;

/**
 * 停留所の基本情報
 * @name 停留所名
 * @type 停留所タイプ (バス停, 鉄道駅など)
 * @lat 緯度
 * @lon 経度
 */
public class Stop {
    public final String name;
    public final String type;
    public final double lat;
    public final double lon;
    
    public Stop(String name, String type, double lat, double lon) {
        this.name = name;
        this.type = type;
        this.lat = lat;
        this.lon = lon;
        }
    }