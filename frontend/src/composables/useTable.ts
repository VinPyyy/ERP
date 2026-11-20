import { reactive, ref, onMounted } from 'vue'

export interface PageResult<T> {
  total: number
  records: T[]
}

export interface TableQuery {
  pageNum: number
  pageSize: number
  [key: string]: unknown
}

/**
 * 列表页统一 composable（spec §7）：分页 + 查询 + 加载状态。
 * fetcher 返回后端 PageResult（http 拦截器已剥掉 R 外壳）。
 */
export function useTable<T>(
  fetcher: (query: TableQuery) => Promise<PageResult<T>>,
  initialQuery: Record<string, unknown> = {},
  options: { immediate?: boolean } = { immediate: true }
) {
  const loading = ref(false)
  const rows = ref<T[]>([]) as { value: T[] }
  const total = ref(0)
  const query = reactive<TableQuery>({ pageNum: 1, pageSize: 20, ...initialQuery })

  async function load() {
    loading.value = true
    try {
      const page = await fetcher({ ...query })
      rows.value = page.records
      total.value = page.total
    } finally {
      loading.value = false
    }
  }

  function search() {
    query.pageNum = 1
    return load()
  }

  function reset() {
    Object.keys(query).forEach((k) => {
      if (k !== 'pageNum' && k !== 'pageSize') delete query[k]
    })
    Object.assign(query, initialQuery)
    return search()
  }

  if (options.immediate !== false) {
    onMounted(load)
  }

  return { loading, rows, total, query, load, search, reset }
}
