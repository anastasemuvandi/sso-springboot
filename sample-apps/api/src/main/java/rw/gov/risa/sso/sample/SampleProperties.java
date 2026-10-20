package rw.gov.risa.sso.sample;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code sample.*} — the ministry's display name and its demo records. */
@ConfigurationProperties(prefix = "sample")
public record SampleProperties(
    @DefaultValue("GoRSSO sample") String displayName,
    @DefaultValue List<DemoRecord> records) {

  public record DemoRecord(String id, String title, String kind) {}
}
