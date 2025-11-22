package com.homeexpress.home_express_api.controller;

import com.homeexpress.home_express_api.dto.location.DistrictDto;
import com.homeexpress.home_express_api.dto.location.ProvinceDto;
import com.homeexpress.home_express_api.dto.location.WardDto;
import com.homeexpress.home_express_api.service.VnLocationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/locations")
public class LocationController {

    private final VnLocationService vnLocationService;

    public LocationController(VnLocationService vnLocationService) {
        this.vnLocationService = vnLocationService;
    }

    @GetMapping("/provinces")
    public ResponseEntity<List<ProvinceDto>> getProvinces() {
        List<ProvinceDto> provinces = vnLocationService.getAllProvinces();
        return ResponseEntity.ok(provinces);
    }

    @GetMapping("/provinces/{provinceCode}/districts")
    public ResponseEntity<List<DistrictDto>> getDistrictsByPath(@PathVariable String provinceCode) {
        List<DistrictDto> districts = vnLocationService.getDistrictsByProvince(provinceCode);
        return ResponseEntity.ok(districts);
    }

    @GetMapping("/districts/{districtCode}/wards")
    public ResponseEntity<List<WardDto>> getWardsByPath(@PathVariable String districtCode) {
        List<WardDto> wards = vnLocationService.getWardsByDistrict(districtCode);
        return ResponseEntity.ok(wards);
    }

    @GetMapping("/districts")
    public ResponseEntity<List<DistrictDto>> getDistricts(@RequestParam String provinceCode) {
        List<DistrictDto> districts = vnLocationService.getDistrictsByProvince(provinceCode);
        return ResponseEntity.ok(districts);
    }

    @GetMapping("/wards")
    public ResponseEntity<List<WardDto>> getWards(@RequestParam String districtCode) {
        List<WardDto> wards = vnLocationService.getWardsByDistrict(districtCode);
        return ResponseEntity.ok(wards);
    }
}
