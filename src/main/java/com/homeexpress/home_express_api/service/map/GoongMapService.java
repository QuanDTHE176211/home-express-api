package com.homeexpress.home_express_api.service.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.homeexpress.home_express_api.dto.location.MapPlaceDTO;
import com.homeexpress.home_express_api.repository.VnDistrictRepository;
import com.homeexpress.home_express_api.repository.VnProvinceRepository;
import com.homeexpress.home_express_api.repository.VnWardRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    private static final CityBias HANOI = new CityBias(
            "ha-noi",
            List.of("ha noi", "hanoi", "tp ha noi", "thanh pho ha noi"),
            21.027763, 105.834160, 60000);

    private static final CityBias HO_CHI_MINH = new CityBias(
            "ho-chi-minh",
            List.of("ho chi minh", "ho chi minh city", "tp ho chi minh", "tp hcm", "hcm", "hcmc", "sai gon", "saigon"),
            10.823099, 106.629662, 60000);

    private static final List<CityBias> SUPPORTED_CITIES = List.of(HANOI, HO_CHI_MINH);

    @Override
    public List<MapPlaceDTO> searchPlaces(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        String normalizedQuery = normalizeForMatch(query);
        List<MapPlaceDTO> filteredResults = new ArrayList<>();
        Set<String> seenPlaceIds = new HashSet<>();

        List<CityBias> prioritizedCities = prioritizeCities(normalizedQuery);
        for (CityBias cityBias : prioritizedCities) {
            List<MapPlaceDTO> cityResults = fetchAutocompleteResults(query, cityBias);
            appendResultsForCity(filteredResults, seenPlaceIds, cityResults, cityBias);
        }

        if (filteredResults.size() < 6) {
            List<MapPlaceDTO> fallbackResults = fetchAutocompleteResults(query, null);
            appendSupportedResults(filteredResults, seenPlaceIds, fallbackResults);
        }

        return filteredResults;
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
                JsonNode result = response.path("result");
                JsonNode location = result.path("geometry").path("location");

                MapPlaceDTO dto = MapPlaceDTO.builder()
                        .placeId(result.path("place_id").asText(""))
                        .description(result.path("formatted_address").asText(""))
                        .latitude(location.path("lat").asDouble())
                        .longitude(location.path("lng").asDouble())
                        .build();

                if (result.has("compound")) {
                    resolveLocationCodes(dto, result.get("compound"));
                }

                return dto;
            }
        } catch (RestClientException e) {
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
                JsonNode results = response.path("results");
                if (results.isArray() && results.size() > 0) {
                    JsonNode firstResult = results.get(0);
                    MapPlaceDTO dto = MapPlaceDTO.builder()
                            .placeId(firstResult.path("place_id").asText(""))
                            .description(firstResult.path("formatted_address").asText(""))
                            .latitude(lat)
                            .longitude(lng)
                            .build();

                    if (firstResult.has("compound")) {
                        resolveLocationCodes(dto, firstResult.get("compound"));
                    }

                    return dto;
                }
            }
        } catch (RestClientException e) {
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
                    .queryParam("vehicle", "car")
                    .toUriString();

            JsonNode response = restTemplate.getForObject(url, JsonNode.class);
            if (response != null && response.has("rows")) {
                JsonNode rows = response.path("rows");
                if (rows.isArray() && rows.size() > 0) {
                    JsonNode elements = rows.get(0).path("elements");
                    if (elements.isArray() && elements.size() > 0) {
                        JsonNode element = elements.get(0);
                        if ("OK".equals(element.path("status").asText())
                                && element.path("distance").has("value")) {
                            return element.path("distance").path("value").asLong();
                        }
                    }
                }
            }
        } catch (RestClientException e) {
            log.error("Error calling Goong DistanceMatrix API", e);
        }
        return calculateHaversineDistance(originLat, originLng, destLat, destLng);
    }

    private long calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000;
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
            String provinceName = compound.path("province").asText();
            if (StringUtils.hasText(provinceName)) {
                String normalizedProvince = normalizeLocationName(provinceName);
                provinceRepository.findFirstByNameContainingIgnoreCase(normalizedProvince)
                    .ifPresent(province -> {
                        dto.setProvinceCode(province.getCode());

                        String districtName = compound.path("district").asText();
                        if (StringUtils.hasText(districtName)) {
                            String normalizedDistrict = normalizeLocationName(districtName);
                            districtRepository.findFirstByNameContainingIgnoreCaseAndProvinceCode(normalizedDistrict, province.getCode())
                                .ifPresent(district -> {
                                    dto.setDistrictCode(district.getCode());

                                    String wardName = compound.path("commune").asText();
                                    if (StringUtils.hasText(wardName)) {
                                        String normalizedWard = normalizeLocationName(wardName);
                                        wardRepository.findFirstByNameContainingIgnoreCaseAndDistrictCode(normalizedWard, district.getCode())
                                            .ifPresent(ward -> dto.setWardCode(ward.getCode()));
                                    }
                                });
                        }
                    });
            }
        } catch (RuntimeException e) {
            log.error("Error resolving location codes from compound: {}", compound, e);
        }
    }

    private String normalizeLocationName(String name) {
        if (!StringUtils.hasText(name)) {
            return "";
        }
        String sanitized = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String normalized = sanitized.replaceAll("^(?i)(tinh|thanh pho|quan|huyen|thi xa|phuong|xa|thi tran)\\s+", "");
        return normalized.trim();
    }

    private List<CityBias> prioritizeCities(String normalizedQuery) {
        List<CityBias> prioritized = new ArrayList<>(SUPPORTED_CITIES);
        for (CityBias city : SUPPORTED_CITIES) {
            if (city.matches(normalizedQuery)) {
                prioritized.remove(city);
                prioritized.add(0, city);
                break;
            }
        }
        return prioritized;
    }

    private List<MapPlaceDTO> fetchAutocompleteResults(String query, CityBias cityBias) {
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(autocompleteUrl)
                    .queryParam("api_key", apiKey)
                    .queryParam("input", query);

            if (cityBias != null) {
                builder.queryParam("location", cityBias.latitude() + "," + cityBias.longitude())
                        .queryParam("radius", cityBias.radiusMeters());
            }

            JsonNode response = restTemplate.getForObject(builder.toUriString(), JsonNode.class);
            return extractPredictions(response);
        } catch (RestClientException e) {
            log.error("Error calling Goong Autocomplete API for {}", cityBias != null ? cityBias.id() : "default", e);
            return List.of();
        }
    }

    private List<MapPlaceDTO> extractPredictions(JsonNode response) {
        List<MapPlaceDTO> results = new ArrayList<>();

        if (response != null && response.has("predictions")) {
            for (JsonNode prediction : response.get("predictions")) {
                String description = prediction.path("description").asText("");

                if (!StringUtils.hasText(description)) {
                    continue;
                }
                if (description.contains("http://") || description.contains("https://")) {
                    continue;
                }

                JsonNode formatting = prediction.path("structured_formatting");
                results.add(MapPlaceDTO.builder()
                        .placeId(prediction.path("place_id").asText(""))
                        .description(description)
                        .mainText(formatting.path("main_text").asText(""))
                        .secondaryText(formatting.path("secondary_text").asText(""))
                        .build());
            }
        }
        return results;
    }

    private void appendResultsForCity(List<MapPlaceDTO> target,
                                      Set<String> seenPlaceIds,
                                      List<MapPlaceDTO> candidates,
                                      CityBias cityBias) {
        for (MapPlaceDTO place : candidates) {
            if (!isInCity(place, cityBias)) {
                continue;
            }
            if (seenPlaceIds.add(place.getPlaceId())) {
                target.add(place);
            }
        }
    }

    private void appendSupportedResults(List<MapPlaceDTO> target,
                                        Set<String> seenPlaceIds,
                                        List<MapPlaceDTO> candidates) {
        for (MapPlaceDTO place : candidates) {
            if (!isInSupportedCities(place)) {
                continue;
            }
            if (seenPlaceIds.add(place.getPlaceId())) {
                target.add(place);
            }
        }
    }

    private boolean isInCity(MapPlaceDTO place, CityBias cityBias) {
        if (place == null || cityBias == null) {
            return false;
        }
        String normalized = normalizeForMatch(buildSearchableText(place));
        return cityBias.matches(normalized);
    }

    private boolean isInSupportedCities(MapPlaceDTO place) {
        if (place == null) {
            return false;
        }
        String normalized = normalizeForMatch(buildSearchableText(place));
        for (CityBias city : SUPPORTED_CITIES) {
            if (city.matches(normalized)) {
                return true;
            }
        }
        return false;
    }

    private String buildSearchableText(MapPlaceDTO place) {
        StringBuilder text = new StringBuilder();
        if (StringUtils.hasText(place.getDescription())) {
            text.append(place.getDescription()).append(' ');
        }
        if (StringUtils.hasText(place.getSecondaryText())) {
            text.append(place.getSecondaryText());
        }
        return text.toString();
    }

    private String normalizeForMatch(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String noDiacritics = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return noDiacritics
                .replaceAll("[^a-zA-Z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
    }

    private record CityBias(String id, List<String> normalizedKeywords, double latitude, double longitude, int radiusMeters) {
        boolean matches(String normalizedText) {
            if (!StringUtils.hasText(normalizedText)) {
                return false;
            }
            for (String keyword : normalizedKeywords) {
                if (normalizedText.contains(keyword)) {
                    return true;
                }
            }
            return false;
        }
    }
}
