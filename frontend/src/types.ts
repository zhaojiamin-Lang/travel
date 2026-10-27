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
  city: string
  days?: number
  startDate?: string
  endDate?: string
  people?: number
  budget?: number
  pace?: 'RELAXED' | 'BALANCED'
  noSpicy?: boolean
  mustSee: string[]
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
