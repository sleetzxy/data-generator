package com.datagenerator.core.constraint.spatial;

import com.datagenerator.spi.constraint.ConstraintValidator;
import com.datagenerator.spi.model.ConstraintContext;
import com.datagenerator.spi.model.ConstraintResult;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.WKTReader;

import java.util.Map;

/**
 * 空间约束：点位于参考多边形/几何体内（within）。
 */
public class WithinSpatialValidator implements ConstraintValidator {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private final Map<String, Geometry> geometryRefs;

    public WithinSpatialValidator(Map<String, Geometry> geometryRefs) {
        this.geometryRefs = geometryRefs;
    }

    public WithinSpatialValidator() {
        this(Map.of());
    }

    @Override
    public String type() {
        return "within";
    }

    @Override
    public ConstraintResult validate(ConstraintContext ctx, Map<String, Object> ruleConfig) {
        String field = String.valueOf(ruleConfig.get("field"));
        String geometryRef = String.valueOf(ruleConfig.get("geometry_ref"));
        Object location = ctx.currentRow().get(field);
        if (location == null) {
            return ConstraintResult.invalid("Spatial field is null: " + field);
        }

        Geometry boundary = geometryRefs.get(geometryRef);
        if (boundary == null) {
            return ConstraintResult.invalid("Unknown geometry reference: " + geometryRef);
        }

        Point point = toPoint(location);
        if (point == null) {
            return ConstraintResult.invalid("Cannot parse point for field: " + field);
        }
        return boundary.contains(point)
                ? ConstraintResult.valid()
                : ConstraintResult.invalid("Point not within geometry: " + geometryRef);
    }

    private static Point toPoint(Object location) {
        if (location instanceof Point point) {
            return point;
        }
        if (location instanceof Map<?, ?> map) {
            Object x = map.get("x");
            Object y = map.get("y");
            if (x instanceof Number xNumber && y instanceof Number yNumber) {
                return GEOMETRY_FACTORY.createPoint(new Coordinate(xNumber.doubleValue(), yNumber.doubleValue()));
            }
            Object lon = map.get("lon");
            Object lat = map.get("lat");
            if (lon instanceof Number lonNumber && lat instanceof Number latNumber) {
                return GEOMETRY_FACTORY.createPoint(new Coordinate(lonNumber.doubleValue(), latNumber.doubleValue()));
            }
        }
        if (location instanceof String wkt) {
            try {
                Geometry geometry = new WKTReader().read(wkt);
                return geometry instanceof Point point ? point : geometry.getCentroid();
            } catch (Exception exception) {
                return null;
            }
        }
        return null;
    }
}
