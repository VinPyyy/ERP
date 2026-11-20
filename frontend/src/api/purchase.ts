import http from './http'

/** 采购链路 API（M4）。http 拦截器已剥 R 外壳。 */

export type DocStatus = 'DRAFT' | 'AUDITED' | 'COMPLETED' | 'VOIDED'

export interface DocHead {
  id: number
  docNo: string
  status: DocStatus
  docDate: string
  supplierId?: number
  warehouseId?: number
  sourceOrderId?: number | null
  sourceInboundId?: number | null
  title?: string
  voidReason?: string
}
export interface DocItem {
  id: number
  lineNo: number
  skuId: number
  skuName?: string
  unit?: string
  supplierId?: number
  qty: number
  price?: number | null
  amount?: number | null
  doneQty?: number
  lineStatus?: number
  lotNo?: string
  snList?: string[]
  reason?: string
  selected?: number
  sourceOrderItemId?: number | null
  sourceInboundItemId?: number | null
}
export interface DocVO {
  doc: DocHead
  items: DocItem[]
}
export type InquiryVO = DocVO
export type OrderVO = DocVO
export type InboundVO = DocVO
export type ReturnVO = DocVO

interface Page<T> {
  total: number
  records: T[]
}

export const inquiryApi = {
  page: (params: Record<string, unknown>) => http.get('/purchase/inquiries', { params }) as any as Promise<Page<DocHead>>,
  get: (id: number) => http.get(`/purchase/inquiries/${id}`) as any as Promise<DocVO>,
  create: (body: unknown) => http.post('/purchase/inquiries', body) as any as Promise<number>,
  audit: (id: number) => http.post(`/purchase/inquiries/${id}/audit`),
  void: (id: number, reason: string) => http.post(`/purchase/inquiries/${id}/void`, { reason }),
  select: (id: number, itemId: number, selected: boolean) =>
    http.put(`/purchase/inquiries/${id}/items/${itemId}/select`, { selected }),
  convert: (id: number, warehouseId: number) =>
    http.post(`/purchase/inquiries/${id}/convert-to-orders`, { warehouseId }) as any as Promise<number[]>
}

export const orderApi = {
  page: (params: Record<string, unknown>) => http.get('/purchase/orders', { params }) as any as Promise<Page<DocHead>>,
  get: (id: number) => http.get(`/purchase/orders/${id}`) as any as Promise<DocVO>,
  create: (body: unknown) => http.post('/purchase/orders', body) as any as Promise<number>,
  audit: (id: number) => http.post(`/purchase/orders/${id}/audit`),
  void: (id: number, reason: string) => http.post(`/purchase/orders/${id}/void`, { reason }),
  closeItem: (id: number, itemId: number) => http.post(`/purchase/orders/${id}/items/${itemId}/close`),
  createInbound: (id: number) => http.post(`/purchase/orders/${id}/create-inbound`) as any as Promise<number>
}

export const inboundApi = {
  page: (params: Record<string, unknown>) => http.get('/purchase/inbounds', { params }) as any as Promise<Page<DocHead>>,
  get: (id: number) => http.get(`/purchase/inbounds/${id}`) as any as Promise<DocVO>,
  create: (body: unknown) => http.post('/purchase/inbounds', body) as any as Promise<number>,
  audit: (id: number) => http.post(`/purchase/inbounds/${id}/audit`),
  void: (id: number, reason: string) => http.post(`/purchase/inbounds/${id}/void`, { reason })
}

export const returnApi = {
  page: (params: Record<string, unknown>) => http.get('/purchase/returns', { params }) as any as Promise<Page<DocHead>>,
  get: (id: number) => http.get(`/purchase/returns/${id}`) as any as Promise<DocVO>,
  create: (body: unknown) => http.post('/purchase/returns', body) as any as Promise<number>,
  audit: (id: number) => http.post(`/purchase/returns/${id}/audit`),
  void: (id: number, reason: string) => http.post(`/purchase/returns/${id}/void`, { reason })
}
