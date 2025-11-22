package com.homeexpress.home_express_api.service.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeexpress.home_express_api.dto.location.MapPlaceDTO;
import com.homeexpress.home_express_api.repository.VnDistrictRepository;
import com.homeexpress.home_express_api.repository.VnProvinceRepository;
import com.homeexpress.home_express_api.repository.VnWardRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class GoongMapService implements MapService {

    private final RestTemplate restTemplate;
    private final VnProvinceRepository provinceRepository;
    private final VnDistrictRepository districtRepository;
    private final VnWardRepository wardRepository;

    public GoongMapService(@Qualifier("goongRestTemplate") RestTemplate restTemplate,
                           VnProvinceRepository provinceRepository,
                           VnDistrictRepository districtRepository,
                           VnWardRepository wardRepository) {
        this.restTemplate = restTemplate;
        this.provinceRepository = provinceRepository;
        this.districtRepository = districtRepository;
        this.wardRepository = wardRepository;
    }

    @Value("${goong.api.key}")
    private String apiKey;

    @Value("${goong.api.url.place.autocomplete}")
    private String autocompleteUrl;

    @Value("${goong.api.url.place.detail}")
    private String placeDetailUrl;

    @Value("${goong.api.url.geocode}")
    private String geocodeUrl;

    @Value("${goong.api.url.distancematrix}")
    private String distanceMatrixUrl;

    @Override
    public List<MapPlaceDTO> searchPlaces(String query) {
        try {
            String url = UriComponentsBuilder.fromUriString(autocompleteUrl)
                    .queryParam("api_key", apiKey)
                    .queryParam("input", query)
                    .toUriString();

            JsonNode response = restTemplate.getForObject(url, JsonNode.class);
            List<MapPlaceDTO> results = new ArrayList<>();

            if (response != null && response.has("predictions")) {
                for (JsonNode prediction : response.get("predictions")) {
                    String description = prediction.get("description").asText();
                    
                    // Filter out bad results containing URLs
                    if (description.contains("http://") || description.contains("https://")) {
                        continue;
                    }

                    results.add(MapPlaceDTO.builder()
                            .placeId(prediction.get("place_id").asText())
                            .description(description)
                            .mainText(prediction.get("structured_formatting").get("main_text").asText())
                            .secondaryText(prediction.path("structured_formatting").path("secondary_text").asText(""))
                            .build());
                }
            }
            return results;
        } catch (Exception e) {
            log.error("Error calling Goong Autocomplete API", e);
            return List.of();
        }
    }

    @Override
    public MapPlaceDTO getPlaceDetails(String placeId) {
        try {
            String url = UriComponentsBuilder.fromUriString(placeDetailUrl)
                    .queryParam("api_key", apiKey)
                    .queryParam("place_id", placeId)
                    .toUriString();

            JsonNode response = restTemplate.getForObject(url, JsonNode.class);
            if (response != null && response.has("result")) {
                JsonNode result = response.get("result");
                JsonNode location = result.get("geometry").get("location");
                
                MapPlaceDTO dto = MapPlaceDTO.builder()
                        .placeId(result.get("place_id").asText())
                        .description(result.get("formatted_address").asText())
                        .latitude(location.get("lat").asDouble())
                        .longitude(location.get("lng").asDouble())
                        .build();

                // Resolve compound address to codes
                if (result.has("compound")) {
                    resolveLocationCodes(dto, result.get("compound"));
                }

                return dto;
            }
        } catch (Exception e) {
            log.error("Error calling Goong Detail API", e);
        }
        return null;
    }

    @Override
    public MapPlaceDTO getAddressFromCoordinates(double lat, double lng) {
        try {
            String latlng = lat + "," + lng;
            String url = UriComponentsBuilder.fromUriString(geocodeUrl)
                    .queryParam("api_key", apiKey)
                    .queryParam("latlng", latlng)
                    .toUriString();

            JsonNode response = restTemplate.getForObject(url, JsonNode.class);
            if (response != null && response.has("results")) {
                JsonNode firstResult = response.get("results").get(0);
                if (firstResult != null) {
                    MapPlaceDTO dto = MapPlaceDTO.builder()
                            .placeId(firstResult.get("place_id").asText())
                            .description(firstResult.get("formatted_address").asText())
                            .latitude(lat)
                            .longitude(lng)
                            .build();
                    
                     // Resolve compound address to codes if available
                    if (firstResult.has("compound")) {
                        resolveLocationCodes(dto, firstResult.get("compound"));
                    }
                    
                    return dto;
                }
            }
        } catch (Exception e) {
            log.error("Error calling Goong Geocode API", e);
        }
        return null;
    }

    @Override
    public long calculateDistanceInMeters(double originLat, double originLng, double destLat, double destLng) {
        try {
            String origins = originLat + "," + originLng;
            String destinations = destLat + "," + destLng;

            String url = UriComponentsBuilder.fromUriString(distanceMatrixUrl)
                    .queryParam("api_key", apiKey)
                    .queryParam("origins", origins)
                    .queryParam("destinations", destinations)
                    .queryParam("vehicle", "car") // Goong supports 'car', 'bike', 'taxi', 'truck'
                    .toUriString();

            JsonNode response = restTemplate.getForObject(url, JsonNode.class);
            if (response != null && response.has("rows")) {
                JsonNode element = response.get("rows").get(0).get("elements").get(0);
                if ("OK".equals(element.get("status").asText())) {
                    return element.get("distance").get("value").asLong();
                }
            }
        } catch (Exception e) {
            log.error("Error calling Goong DistanceMatrix API", e);
        }
        // Fallback: Calculate Haversine distance if API fails
        return calculateHaversineDistance(originLat, originLng, destLat, destLng);
    }

    private long calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000; // Radius of the earth in meters
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (long) (R * c);
    }
    
    private void resolveLocationCodes(MapPlaceDTO dto, JsonNode compound) {
        try {
            // 1. Resolve Province
            String provinceName = compound.path("province").asText();
            if (StringUtils.hasText(provinceName)) {
                String normalizedProvince = normalizeLocationName(provinceName);
                provinceRepository.findFirstByProvinceNameContainingIgnoreCase(normalizedProvince)
                    .ifPresent(province -> {
                        dto.setProvinceCode(province.getProvinceCode());
                        
                        // 2. Resolve District (only if province found)
                        String districtName = compound.path("district").asText();
                        if (StringUtils.hasText(districtName)) {
                            String normalizedDistrict = normalizeLocationName(districtName);
                            districtRepository.findFirstByDistrictNameContainingIgnoreCaseAndProvinceCode(normalizedDistrict, province.getProvinceCode())
                                .ifPresent(district -> {
                                    dto.setDistrictCode(district.getDistrictCode());
                                    
                                    // 3. Resolve Ward (only if district found)
                                    String wardName = compound.path("commune").asText();
                                    if (StringUtils.hasText(wardName)) {
                                        String normalizedWard = normalizeLocationName(wardName);
                                        wardRepository.findFirstByWardNameContainingIgnoreCaseAndDistrictCode(normalizedWard, district.getDistrictCode())
                                            .ifPresent(ward -> dto.setWardCode(ward.getWardCode()));
                                    }
                                });
                        }
                    });
            }
        } catch (Exception e) {
            log.error("Error resolving location codes from compound: {}", compound, e);
        }
    }
    
    private String normalizeLocationName(String name) {
        if (name == null) return "";
        // Remove common prefixes to improve matching
        String normalized = name.replaceAll("^(Tỉnh|Thành phố|Thành Phố|Quận|Huyện|Thị xã|Thị Xã|Phường|Xã|Thị trấn|Thị Trấn)\\s+", "");
        return normalized.trim();
    }
}
