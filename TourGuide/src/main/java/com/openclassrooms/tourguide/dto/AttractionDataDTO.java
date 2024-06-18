package com.openclassrooms.tourguide.dto;

import gpsUtil.location.Location;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class AttractionDataDTO {
    private  String attractionName;
    private  Location attractionLocation;
    private  Location userLocation;
    private  double distance;
    private  int rewardPoint;
}
