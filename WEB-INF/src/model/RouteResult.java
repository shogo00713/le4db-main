package model;

import java.util.List;

public class RouteResult {
    public Stop originStop;
    public Stop destStop;
    public List<ResultItem> results;
    public List<ResultItem> displayedResults;
    public List<NearByStops> stopsNearOrigin;
    public List<NearByStops> stopsNearDest;
    public List<PortCandidate> portsNearOrigin;
    public List<PortCandidate> portsNearDest;
    public String lastOriginStopName;
    public String lastOriginStopType;
    public String lastDestStopName;
    public String lastDestStopType;
}