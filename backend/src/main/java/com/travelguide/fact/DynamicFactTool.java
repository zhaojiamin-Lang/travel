package com.travelguide.fact;

import com.travelguide.fact.FactModels.FactQuery;
import com.travelguide.fact.FactModels.FactRecord;

import java.util.List;

/**
 * 天气、开放状态、票价和路线等外部事实工具的统一接口。
 */
public interface DynamicFactTool {

    String name();

    boolean supports(FactQuery query);

    List<FactRecord> fetch(FactQuery query);
}
