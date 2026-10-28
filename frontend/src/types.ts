/**
 * 模块：共享类型（types.ts）
 *
 * 职责：前后端契约的唯一声明处，与后端 web/ApiDtos 的 record 一一对应——
 * Poi、Draft、Plan/PlanItem/DayPlan、GuideSession、User、HistoryItem、
 * Bootstrap、MapPoint 等。改接口时先改这里，TypeScript 会把受影响的地方全部报出来。
 */
export interface Poi {
  id: string
  name: string
  type: string
  area: string
  latitude: number
  longitude: number
  durationMinutes: number
  ticketPrice: number
  openTime: string
  closeTime: string
  tags: string[]
  accent: string
  summary: string
  history: string
  sourceName: string
  sourceUrl?: string
  dataConfidence: string
}

export interface Draft {
  requestId: string
  city?: string
  days?: number
  startDate?: string
  endDate?: string
  people?: number
  budget?: number
  pace?: 'RELAXED' | 'BALANCED'
  noSpicy?: boolean
  mustSee: string[]
  interestTags: string[]
  excludedPoiIds: string[]
  missingFields: string[]
  summary: string
  suggestedPois: Poi[]
}

export interface Transit {
  mode: string
  label: string
  minutes: number
}

export interface PlanItem {
  id: string
  type: 'POI' | 'MEAL'
  poiId?: string
  name: string
  shortName: string
  area: string
  startTime: string
  endTime: string
  durationMinutes: number
  cost: number
  note: string
  sourceName: string
  sourceUrl?: string
  accent: string
  latitude: number
  longitude: number
  transitFromPrevious?: Transit
}

export interface DayPlan {
  dayNumber: number
  date: string
  label: string
  title: string
  summary: string
  items: PlanItem[]
}

export interface PlanIssue {
  code: string
  severity: 'INFO' | 'WARNING' | 'ERROR'
  message: string
  suggestion: string
}

export interface MapPoint {
  poiId: string
  name: string
  latitude: number
  longitude: number
  dayNumber: number
  order: number
}

export interface Plan {
  id: string
  requestId: string
  version: number
  title: string
  subtitle: string
  city: string
  startDate: string
  days: number
  people: number
  budget: number
  estimatedCost: number
  pace: string
  mustSee: string[]
  dayPlans: DayPlan[]
  issues: PlanIssue[]
  passed: boolean
  mapPoints: MapPoint[]
  generatedBy: string
}

export interface Bootstrap {
  city: string
  seasonLabel: string
  curatedPoiCount: number
  featuredPois: Poi[]
  suggestedPrompts: string[]
  mapKey?: string
  aiMode: string
}

export interface LocationCandidate {
  poi: Poi
  distanceMeters: number
  confidence: number
}

export interface LocationResult {
  status: 'FOUND' | 'NEARBY' | 'NOT_FOUND'
  message: string
  candidates: LocationCandidate[]
}

export interface GuideContent {
  poiId: string
  name: string
  area: string
  eyebrow: string
  narrative: string
  history: string
  opening: string
  ticket: string
  sourceName: string
  sourceUrl?: string
  confidence: string
  latitude: number
  longitude: number
  nearby: Poi[]
}

export interface GuideSession {
  id: string
  planId: string
  status: string
  currentPoi?: GuideContent
}

export interface User {
  id: string
  email?: string
  displayName: string
  guest: boolean
}

export interface HistoryItem {
  requestId: string
  latestPlanId?: string
  summary: string
  startDate?: string
  createdAt: string
  planCount: number
}

export interface SemanticChange {
  type: string
  dayNumber: number
  itemId?: string
  before?: string
  after?: string
  description: string
}

export interface PlanDiff {
  fromPlanId: string
  toPlanId: string
  fromVersion: number
  toVersion: number
  identical: boolean
  changes: SemanticChange[]
  summary: {
    additions: number
    removals: number
    replacements: number
    timeChanges: number
    otherChanges: number
    costDelta: number
  }
}

export interface RollbackResult {
  createdNewVersion: boolean
  currentPlan: Plan
  appliedChanges: PlanDiff
}

export interface PlanIntent {
  intent: string
  confidence: number
  needsConfirmation: boolean
  parameters: Record<string, unknown>
  note: string
}

export interface PlanExplanation {
  explanation: string
  source: 'QWEN_SPRING_AI' | 'DETERMINISTIC_FALLBACK'
  diff: PlanDiff
}

export interface WeatherCast {
  date: string
  weekday: string
  dayWeather: string
  nightWeather: string
  dayTemperature: string
  nightTemperature: string
  dayWind: string
  nightWind: string
}

export interface WeatherSnapshot {
  city: string
  adcode: string
  status: 'FRESH' | 'STALE' | 'ERROR' | 'UNCONFIGURED'
  source: string
  lastAttemptAt?: string
  lastSuccessAt?: string
  forecasts: WeatherCast[]
  error?: string
}

export interface OptimizedStop {
  itemId: string
  poiId: string
  name: string
  startTime: string
  endTime: string
  durationMinutes: number
  travelMinutesFromPrevious: number
  originalPosition: number
  optimizedPosition: number
}

export interface DayOptimization {
  planId: string
  dayNumber: number
  solver: string
  status: string
  objectiveValue: number
  solveSeconds: number
  stopCount: number
  stops: OptimizedStop[]
  notes: string[]
}

export interface ExecutionProgress {
  id: string
  planId: string
  currentItemId?: string
  completedItemIds: string[]
  status: 'NOT_STARTED' | 'IN_PROGRESS' | 'PAUSED' | 'COMPLETED'
  currentItemStartedAt?: string
  actualEndedAt?: string
  delayMinutes: number
  lastEventId?: string
  revision: number
  updatedAt: string
}

export interface ReplanProposal {
  id: string
  eventId: string
  planId: string
  eventType: 'CLOSURE' | 'DELAY'
  baseVersion: number
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED'
  summary: string
  changes: SemanticChange[]
  proposedPlan: Plan
  createdAt: string
}
