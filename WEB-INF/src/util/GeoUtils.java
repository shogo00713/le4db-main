package util;

public class GeoUtils {
    
    /**
     * 2地点間の直線距離を Haversine で計算 (メートル) 
     * @param lat1 : 緯度1
     * @param lon1 : 経度1
     * @param lat2 : 緯度2
     * @param lon2 : 経度2
     */
    public static int distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000.0; // 地球の半径
        
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double deltaLatRad = Math.toRadians(lat2 - lat1);
        double deltaLonRad = Math.toRadians(lon2 - lon1);
        
        double a = Math.sin(deltaLatRad / 2) * Math.sin(deltaLatRad / 2)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad)
                * Math.sin(deltaLonRad / 2) * Math.sin(deltaLonRad / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        
        return (int) Math.round(R * c);
    }
    
    /**
     * 徒歩時間を計算（分）
     * @param distanceMeters : 直線距離 (メートル)
     * @param correction     : 道のり補正係数 (通常1.25)
     * @param meterPerMinute : 徒歩速度 (メートル/分, 通常80)
     */
    public static int walkingMinutes(double distanceMeters, double correction, double meterPerMinute) {
    if (distanceMeters <= 0)
            return 0;
        int minutes = (int) Math.ceil(distanceMeters * correction / meterPerMinute);
        return Math.max(0, minutes);
    }
    
    /**
     * 自転車移動時間を計算（分）
     * @param distanceMeters : 直線距離 (メートル)
     * @param correction     : 道のり補正係数 (通常1.5)
     * @param meterPerMinute : 自転車速度 (メートル/分, 通常250)
     */
    public static int ridingMinutes(double distanceMeters, double correction, double meterPerMinute) {
    if (distanceMeters <= 0)
            return 0;
        int minutes = (int) Math.ceil(distanceMeters * correction / meterPerMinute);
        return Math.max(0, minutes);
    }
}