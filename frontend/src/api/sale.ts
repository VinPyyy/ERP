import http from './http'

/** 销售链路 API（M5）。http 拦截器已剥 R 外壳；状态为枚举名字符串。 */

export type SaleDocStatus = 'DRAFT' | 'AUDITED' | 'COMPLETED' | 'VOIDED'

export interface SaleDocHead {
  id: number
  docNo: string
  status: SaleDocStatus
  docDate: string
  customerId?: number
  warehouseId?: number | null
  sourceOrderId?: number | null
  sourceOutboundId?: number | null
  validUntil?: string | null
  carrier?: string | null
  trackingNo?: string | null
  voidReason?: string
}
export interface SaleDocItem {
  id: number
  lineNo: number
  skuId: number
  skuName?: string
  unit?: string
  qty: number
  price?: number | null
  amount?: number | null
  doneQty?: number
  lineStatus?: number
  lotNo?: string | null
  snList?: string[] | null
  reason?: string
  sourceOrderItemId?: number | null
  sourceOutboundItemId?: number | null
}
export interface SaleDocVO {
  doc: SaleDocHead
  items: SaleDocItem[]
}
export interface SaleDocOption {
  id: number
  docNo: string
  customerId?: number
  warehouseId?: number
}

export const quoteApi = {
  page: (params: Record<string, unknown>) => http.get('/sale/quotes', { params }) as any as Promise<{ total: number; records: SaleDocHead[] }>,
  get: (id: number) => http.get(`/sale/quotes/${id}`) as any as Promise<SaleDocVO>,
  create: (body: unknown) => http.post('/sale/quotes', body) as any as Promise<number>,
  audit: (id: number) => http.post(`/sale/quotes/${id}/audit`),
  void: (id: number, reason: string) => http.post(`/sale/quotes/${id}/void`, { reason }),
  convert: (id: number, warehouseId: number) =>
    http.post(`/sale/quotes/${id}/convert-to-order`, { warehouseId }) as any as Promise<number>
}

export const saleOrderApi = {
  page: (params: Record<string, unknown>) => http.get('/sale/orders', { params }) as any as Promise<{ total: number; records: SaleDocHead[] }>,
  get: (id: number) => http.get(`/sale/orders/${id}`) as any as Promise<SaleDocVO>,
  create: (body: unknown) => http.post('/sale/orders', body) as any as Promise<number>,
  audit: (id: number) => http.post(`/sale/orders/${id}/audit`),
  void: (id: number, reason: string) => http.post(`/sale/orders/${id}/void`, { reason }),
  closeItem: (id: number, itemId: number) => http.post(`/sale/orders/${id}/items/${itemId}/close`),
  createOutbound: (id: number) => http.post(`/sale/orders/${id}/create-outbound`) as any as Promise<number>,
  options: () => http.get('/sale/orders/options') as any as Promise<SaleDocOption[]>
}

export const outboundApi = {
  page: (params: Record<string, unknown>) => http.get('/sale/outbounds', { params }) as any as Promise<{ total: number; records: SaleDocHead[] }>,
  get: (id: number) => http.get(`/sale/outbounds/${id}`) as any as Promise<SaleDocVO>,
  create: (body: unknown) => http.post('/sale/outbounds', body) as any as Promise<number>,
  audit: (id: number) => http.post(`/sale/outbounds/${id}/audit`),
  void: (id: number, reason: string) => http.post(`/sale/outbounds/${id}/void`, { reason }),
  createReturn: (id: number) => http.post(`/sale/outbounds/${id}/create-return`) as any as Promise<number>,
  options: () => http.get('/sale/outbounds/options') as any as Promise<SaleDocOption[]>
}

export const saleReturnApi = {
  page: (params: Record<string, unknown>) => http.get('/sale/returns', { params }) as any as Promise<{ total: number; records: SaleDocHead[] }>,
  get: (id: number) => http.get(`/sale/returns/${id}`) as any as Promise<SaleDocVO>,
  create: (body: unknown) => http.post('/sale/returns', body) as any as Promise<number>,
  audit: (id: number) => http.post(`/sale/returns/${id}/audit`),
  void: (id: number, reason: string) => http.post(`/sale/returns/${id}/void`, { reason })
}

export const saleApi = {
  /** 可用量提示（qty − locked），key=skuId */
  availability: (warehouseId: number, skuIds: number[]) =>
    http.get('/sale/availability', { params: { warehouseId, skuIds: skuIds.join(',') } }) as any as Promise<Record<string, number>>,
  returnableSns: (outboundItemId: number) =>
    http.get(`/sale/outbound-items/${outboundItemId}/returnable-sns`) as any as Promise<string[]>
}
