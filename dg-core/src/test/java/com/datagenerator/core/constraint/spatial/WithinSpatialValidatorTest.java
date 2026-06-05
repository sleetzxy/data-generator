package com.datagenerator.core.constraint.spatial;

import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.DataRow;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WithinSpatialValidatorTest {

    @Test
    void withinSpatialValidator_pointInsideBoundary() {
        GeometryFactory factory = new GeometryFactory();
        Polygon boundary = factory.createPolygon(new Coordinate[] {
            new Coordinate(0, 0),
            new Coordinate(10, 0),
            new Coordinate(10, 10),
            new Coordinate(0, 10),
            new Coordinate(0, 0)
        });
        WithinSpatialValidator validator = new WithinSpatialValidator(Map.of("region_boundary", boundary));

        DataRow row = new DataRow();
        row.set("location", Map.of("x", 5, "y", 5));
        var result = validator.validate(
                new ConstraintContext(row, Map.of(), Map.of()),
                Map.of("field", "location", "geometry_ref", "region_boundary"));

        assertThat(result.isValid()).isTrue();
    }
}
