# WeatherCN 动态事实工具

官方平台：`https://platform.weathercn.com/`

本适配器使用：

```text
API Host: https://openapi.weathercn.com
鉴权 Header: X-Gw-API-Key
定位: /locations/v1/cities/geoposition/search.json
实况: /currentconditions/v1/{locationKey}.json
逐日: /forecasts/v1/daily/{1|5|10}day/{locationKey}.json
```

API Key 必须通过环境变量或本地未提交的 `.env.properties` 提供，不写入源码：

```text
TRAVEL_WEATHERCN_API_KEY
```

`application.yml` 中已使用环境变量占位符：

```yaml
travel:
  weathercn:
    api-key: ${TRAVEL_WEATHERCN_API_KEY:}
    base-url: ${TRAVEL_WEATHERCN_BASE_URL:https://openapi.weathercn.com}
```

配置 Key 后，`WeatherCnConfiguration` 会自动创建天气工具 Bean；未配置时该 Bean
不会创建，不影响现有应用启动。

本地构造示例：

```java
var client = new WeatherCnHttpClient(
        System.getenv("TRAVEL_WEATHERCN_API_KEY"),
        new ObjectMapper()
);
var weatherTool = new WeatherCnDynamicFactTool(client);
var factService = new DynamicFactService(
        new DynamicFactToolRegistry(List.of(weatherTool)),
        new TemporalFactArbiter()
);
```

事实查询需要提供 `locationKey`，或者提供 `latitude`、`longitude`。
逐日预报表还需要 `applicableDate`；`forecastDays` 只允许 1、5、10。
