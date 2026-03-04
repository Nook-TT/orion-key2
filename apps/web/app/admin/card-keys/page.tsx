"use client"

import { useState, useEffect } from "react"
import {
  Upload,
  Eye,
  Ban,
  Lock,
  Unlock,
  Package,
  KeyRound,
  AlertCircle,
  X,
  FileText,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { useLocale } from "@/lib/context"
import { toast } from "sonner"
import { adminCardKeyApi, adminProductApi, withMockFallback } from "@/services/api"
import {
  mockCardKeyStockList,
  mockImportBatchList,
  mockProducts,
} from "@/lib/mock-data"
import { Modal } from "@/components/ui/modal"
import type { CardKeyStockSummary, CardKeyListItem, CardImportBatch, ProductCard, ProductSpec } from "@/types"

export default function AdminCardKeysPage() {
  const { t } = useLocale()
  const [tab, setTab] = useState<"stock" | "import">("stock")
  const [showImportModal, setShowImportModal] = useState(false)
  const [stockList, setStockList] = useState<CardKeyStockSummary[]>([])
  const [importBatches, setImportBatches] = useState<CardImportBatch[]>([])
  const [importTotal, setImportTotal] = useState(0)
  const [importPage, setImportPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const [products, setProducts] = useState<ProductCard[]>([])
  const [filterProductId, setFilterProductId] = useState("")

  // Detail modal state
  const [showDetailModal, setShowDetailModal] = useState(false)
  const [detailItem, setDetailItem] = useState<CardKeyStockSummary | null>(null)
  const [detailKeys, setDetailKeys] = useState<CardKeyListItem[]>([])
  const [detailTotal, setDetailTotal] = useState(0)
  const [detailPage, setDetailPage] = useState(1)
  const [detailLoading, setDetailLoading] = useState(false)
  const [updatingKeyId, setUpdatingKeyId] = useState<string | null>(null)
  const [pendingLockKey, setPendingLockKey] = useState<CardKeyListItem | null>(null)
  const [singleLockNote, setSingleLockNote] = useState("")
  const [singleLocking, setSingleLocking] = useState(false)
  const [bulkAction, setBulkAction] = useState<{ type: "lock" | "unlock"; item: CardKeyStockSummary } | null>(null)
  const [bulkLockNote, setBulkLockNote] = useState("")
  const [bulkProcessing, setBulkProcessing] = useState(false)
  const [selectedDetailKeyIds, setSelectedDetailKeyIds] = useState<string[]>([])
  const [selectedAction, setSelectedAction] = useState<{ type: "lock" | "unlock"; count: number } | null>(null)
  const [selectedActionNote, setSelectedActionNote] = useState("")
  const [selectedActionProcessing, setSelectedActionProcessing] = useState(false)

  // Import form state
  const [importProductId, setImportProductId] = useState("")
  const [importSpecId, setImportSpecId] = useState("")
  const [importContent, setImportContent] = useState("")
  const [importing, setImporting] = useState(false)
  const [importSpecs, setImportSpecs] = useState<ProductSpec[]>([])
  const [loadingSpecs, setLoadingSpecs] = useState(false)

  const fetchStock = async () => {
    try {
      const data = await withMockFallback(
        () => adminCardKeyApi.getStock(filterProductId ? { product_id: filterProductId } : undefined),
        () => mockCardKeyStockList(filterProductId ? { product_id: filterProductId } : undefined)
      )
      setStockList(data)
    } catch {
      setStockList([])
    }
  }

  const fetchImportBatches = async () => {
    try {
      const data = await withMockFallback(
        () => adminCardKeyApi.getImportBatches({ page: importPage, page_size: 20 }),
        () => mockImportBatchList({ page: importPage, page_size: 20 })
      )
      setImportBatches(data.list)
      setImportTotal(data.pagination.total)
    } catch {
      setImportBatches([])
    }
  }

  useEffect(() => {
    const init = async () => {
      setLoading(true)
      try {
        const prods = await withMockFallback(
          () => adminProductApi.getList({ page: 1, page_size: 100 }),
          () => ({ list: mockProducts, pagination: { page: 1, page_size: 100, total: mockProducts.length } })
        )
        setProducts(prods.list)
      } catch {
        setProducts([])
      }
      await Promise.all([fetchStock(), fetchImportBatches()])
      setLoading(false)
    }
    init()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => { fetchStock() }, [filterProductId])
  useEffect(() => { fetchImportBatches() }, [importPage])

  // Computed stats from stockList
  const totalKeys = stockList.reduce((s, r) => s + r.total, 0)
  const totalAvailable = stockList.reduce((s, r) => s + r.available, 0)
  const totalSold = stockList.reduce((s, r) => s + r.sold, 0)
  const totalLocked = stockList.reduce((s, r) => s + r.locked, 0)
  const totalInvalid = stockList.reduce((s, r) => s + r.invalid, 0)
  const selectableDetailKeys = detailKeys.filter((key) => key.status === "AVAILABLE" || key.status === "LOCKED")
  const selectedAvailableKeys = detailKeys.filter(
    (key) => selectedDetailKeyIds.includes(key.id) && key.status === "AVAILABLE"
  )
  const selectedLockedKeys = detailKeys.filter(
    (key) => selectedDetailKeyIds.includes(key.id) && key.status === "LOCKED"
  )

  const adjustSummaryCounts = (
    summary: CardKeyStockSummary,
    fromStatus: CardKeyListItem["status"],
    toStatus: CardKeyListItem["status"]
  ) => {
    const nextSummary = { ...summary }

    if (fromStatus === "AVAILABLE") nextSummary.available = Math.max(0, nextSummary.available - 1)
    if (fromStatus === "LOCKED") nextSummary.locked = Math.max(0, nextSummary.locked - 1)
    if (toStatus === "AVAILABLE") nextSummary.available += 1
    if (toStatus === "LOCKED") nextSummary.locked += 1

    return nextSummary
  }

  const updateLocalKeyStatus = (
    keyId: string,
    nextStatus: "AVAILABLE" | "LOCKED",
    nextLockNote?: string | null
  ) => {
    const currentKey = detailKeys.find((key) => key.id === keyId)
    if (!currentKey || currentKey.status === nextStatus || !detailItem) return

    setDetailKeys((prev) =>
      prev.map((key) =>
        key.id === keyId
          ? {
              ...key,
              status: nextStatus,
              lock_note: nextStatus === "LOCKED" ? (nextLockNote ?? null) : null,
            }
          : key
      )
    )

    setDetailItem((prev) => {
      if (!prev) return prev
      return adjustSummaryCounts(prev, currentKey.status, nextStatus)
    })

    setStockList((prev) =>
      prev.map((item) =>
        item.product_id === detailItem.product_id && item.spec_id === detailItem.spec_id
          ? adjustSummaryCounts(item, currentKey.status, nextStatus)
          : item
      )
    )
  }

  const handleToggleKeyStatus = async (
    keyId: string,
    nextStatus: "AVAILABLE" | "LOCKED",
    note?: string | null
  ) => {
    setUpdatingKeyId(keyId)
    try {
      await withMockFallback(
        () => nextStatus === "LOCKED" ? adminCardKeyApi.lock(keyId, note) : adminCardKeyApi.unlock(keyId),
        () => null
      )
      updateLocalKeyStatus(keyId, nextStatus, note)
      toast.success(nextStatus === "LOCKED" ? "已锁定卡密，暂不参与售卖" : "已恢复卡密，可重新售卖")
      return true
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "更新卡密状态失败")
      return false
    } finally {
      setUpdatingKeyId(null)
    }
  }

  const openSingleLockModal = (key: CardKeyListItem) => {
    setPendingLockKey(key)
    setSingleLockNote(key.lock_note ?? "")
  }

  const handleConfirmSingleLock = async () => {
    if (!pendingLockKey) return
    setSingleLocking(true)
    try {
      const success = await handleToggleKeyStatus(pendingLockKey.id, "LOCKED", singleLockNote)
      if (success) {
        setPendingLockKey(null)
        setSingleLockNote("")
      }
    } finally {
      setSingleLocking(false)
    }
  }

  const openBulkActionModal = (type: "lock" | "unlock", item: CardKeyStockSummary) => {
    setBulkAction({ type, item })
    if (type === "lock") {
      setBulkLockNote("")
    }
  }

  const handleBulkAction = async () => {
    if (!bulkAction) return
    setBulkProcessing(true)
    try {
      let affectedCount = 0
      if (bulkAction.type === "lock") {
        const result = await withMockFallback(
          () => adminCardKeyApi.batchLock({
            product_id: bulkAction.item.product_id,
            spec_id: bulkAction.item.spec_id,
            note: bulkLockNote,
          }),
          () => ({ locked_count: bulkAction.item.available })
        )
        affectedCount = result.locked_count
        toast.success(`已批量锁定 ${result.locked_count} 条可用卡密`)
      } else {
        const result = await withMockFallback(
          () => adminCardKeyApi.batchUnlock({
            product_id: bulkAction.item.product_id,
            spec_id: bulkAction.item.spec_id,
          }),
          () => ({ unlocked_count: bulkAction.item.locked })
        )
        affectedCount = result.unlocked_count
        toast.success(`已批量恢复 ${result.unlocked_count} 条锁定卡密`)
      }

      await fetchStock()
      if (
        detailItem &&
        detailItem.product_id === bulkAction.item.product_id &&
        detailItem.spec_id === bulkAction.item.spec_id
      ) {
        const nextDetailItem =
          bulkAction.type === "lock"
            ? {
                ...detailItem,
                available: Math.max(0, detailItem.available - affectedCount),
                locked: detailItem.locked + affectedCount,
              }
            : {
                ...detailItem,
                available: detailItem.available + affectedCount,
                locked: Math.max(0, detailItem.locked - affectedCount),
              }
        setDetailItem(nextDetailItem)
        await fetchDetailKeys(nextDetailItem, detailPage)
      }

      setBulkAction(null)
      setBulkLockNote("")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "批量更新卡密失败")
    } finally {
      setBulkProcessing(false)
    }
  }

  const toggleDetailSelection = (keyId: string) => {
    setSelectedDetailKeyIds((prev) =>
      prev.includes(keyId) ? prev.filter((id) => id !== keyId) : [...prev, keyId]
    )
  }

  const toggleSelectAllDetailKeys = () => {
    const selectableIds = selectableDetailKeys.map((key) => key.id)
    if (selectableIds.length === 0) {
      return
    }
    setSelectedDetailKeyIds((prev) =>
      prev.length === selectableIds.length && selectableIds.every((id) => prev.includes(id))
        ? []
        : selectableIds
    )
  }

  const openSelectedActionModal = (type: "lock" | "unlock") => {
    const count = type === "lock" ? selectedAvailableKeys.length : selectedLockedKeys.length
    if (count === 0) {
      return
    }
    setSelectedAction({ type, count })
    if (type === "lock") {
      setSelectedActionNote("")
    }
  }

  const handleSelectedAction = async () => {
    if (!selectedAction || !detailItem) return
    const targetIds = (selectedAction.type === "lock" ? selectedAvailableKeys : selectedLockedKeys).map((key) => key.id)
    if (targetIds.length === 0) {
      return
    }

    setSelectedActionProcessing(true)
    try {
      if (selectedAction.type === "lock") {
        const result = await withMockFallback(
          () => adminCardKeyApi.lockSelected({ card_key_ids: targetIds, note: selectedActionNote }),
          () => ({ locked_count: targetIds.length })
        )
        toast.success(`已批量锁定 ${result.locked_count} 条选中卡密`)
      } else {
        const result = await withMockFallback(
          () => adminCardKeyApi.unlockSelected({ card_key_ids: targetIds }),
          () => ({ unlocked_count: targetIds.length })
        )
        toast.success(`已批量恢复 ${result.unlocked_count} 条选中卡密`)
      }

      setSelectedAction(null)
      setSelectedActionNote("")
      setSelectedDetailKeyIds([])
      await fetchStock()
      await fetchDetailKeys(detailItem, detailPage)
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "处理选中卡密失败")
    } finally {
      setSelectedActionProcessing(false)
    }
  }

  const handleProductChange = async (productId: string) => {
    setImportProductId(productId)
    setImportSpecId("")
    setImportSpecs([])
    if (!productId) return
    setLoadingSpecs(true)
    try {
      const specs = await withMockFallback(
        () => adminProductApi.getSpecs(productId),
        () => []
      )
      setImportSpecs(specs)
      if (specs.length > 0) setImportSpecId(specs[0].id)
    } catch {
      setImportSpecs([])
    } finally {
      setLoadingSpecs(false)
    }
  }

  const handleImport = async () => {
    if (!importProductId) {
      toast.error("请选择商品")
      return
    }
    if (!importContent.trim()) {
      toast.error("请输入卡密内容")
      return
    }
    setImporting(true)
    try {
      const result = await withMockFallback(
        () => adminCardKeyApi.import({
          product_id: importProductId,
          spec_id: importSpecId || null,
          content: importContent,
        }),
        () => ({
          id: "mock-batch-" + Date.now(),
          product_id: importProductId,
          spec_id: importSpecId || null,
          imported_by: "admin",
          total_count: importContent.trim().split("\n").length,
          success_count: importContent.trim().split("\n").length,
          fail_count: 0,
          fail_detail: null,
          created_at: new Date().toISOString(),
        })
      )
      toast.success(`导入成功：${result.success_count} 条${result.fail_count > 0 ? `，失败 ${result.fail_count} 条` : ""}`)
      setShowImportModal(false)
      setImportContent("")
      setImportProductId("")
      setImportSpecId("")
      await Promise.all([fetchStock(), fetchImportBatches()])
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "导入失败")
    } finally {
      setImporting(false)
    }
  }

  const fetchDetailKeys = async (item: CardKeyStockSummary, page: number) => {
    setDetailLoading(true)
    try {
      const data = await withMockFallback(
        () => adminCardKeyApi.getList({
          product_id: item.product_id,
          spec_id: item.spec_id,
          page,
          page_size: 20,
        }),
        () => ({ list: [], pagination: { page, page_size: 20, total: 0 } })
      )
      setDetailKeys(data.list)
      setDetailTotal(data.pagination.total)
      setSelectedDetailKeyIds((prev) => prev.filter((id) => data.list.some((key) => key.id === id)))
    } catch {
      setDetailKeys([])
      setDetailTotal(0)
      setSelectedDetailKeyIds([])
    } finally {
      setDetailLoading(false)
    }
  }

  const handleViewDetail = (item: CardKeyStockSummary) => {
    setDetailItem(item)
    setDetailPage(1)
    setSelectedDetailKeyIds([])
    setShowDetailModal(true)
    fetchDetailKeys(item, 1)
  }

  const handleDetailPageChange = (page: number) => {
    setDetailPage(page)
    if (detailItem) fetchDetailKeys(detailItem, page)
  }

  // Batch invalidate confirmation state
  const [showInvalidateConfirm, setShowInvalidateConfirm] = useState<CardKeyStockSummary | null>(null)
  const [invalidating, setInvalidating] = useState(false)

  const handleBatchInvalidate = async () => {
    if (!showInvalidateConfirm) return
    setInvalidating(true)
    try {
      const result = await withMockFallback(
        () => adminCardKeyApi.batchInvalidate({
          product_id: showInvalidateConfirm.product_id,
          spec_id: showInvalidateConfirm.spec_id,
        }),
        () => ({ invalidated_count: showInvalidateConfirm.available })
      )
      toast.success(`已作废 ${result.invalidated_count} 条可用卡密`)
      setShowInvalidateConfirm(null)
      await fetchStock()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "作废失败")
    } finally {
      setInvalidating(false)
    }
  }

  if (loading) {
    return (
      <div className="flex flex-col gap-6">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{t("admin.cardKeys")}</h1>
          <p className="text-sm text-muted-foreground">{t("admin.cardKeysDesc")}</p>
        </div>
        <div className="flex items-center justify-center py-24">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{t("admin.cardKeys")}</h1>
          <p className="text-sm text-muted-foreground">{t("admin.cardKeysDesc")}</p>
        </div>
        <button
          type="button"
          className="flex items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground shadow-sm hover:bg-primary/90 transition-colors"
          onClick={() => setShowImportModal(true)}
        >
          <Upload className="h-4 w-4" />
          {t("admin.batchImport")}
        </button>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-5">
        {[
          { label: t("admin.totalKeys"), value: String(totalKeys), icon: KeyRound, color: "text-blue-500" },
          { label: t("admin.availableStock"), value: String(totalAvailable), icon: Package, color: "text-emerald-500" },
          { label: t("admin.soldOut"), value: String(totalSold), icon: FileText, color: "text-muted-foreground" },
          { label: "锁定卡密", value: String(totalLocked), icon: Ban, color: "text-amber-500" },
          { label: t("admin.invalidKeys"), value: String(totalInvalid), icon: AlertCircle, color: "text-red-500" },
        ].map((stat) => (
          <div key={stat.label} className="rounded-xl border border-border bg-card p-4 shadow-sm">
            <div className="flex items-center gap-2">
              <stat.icon className={cn("h-4 w-4", stat.color)} />
              <span className="text-sm text-muted-foreground">{stat.label}</span>
            </div>
            <p className="mt-1 text-2xl font-bold text-foreground">{stat.value}</p>
          </div>
        ))}
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-border">
        {[
          { key: "stock" as const, label: t("admin.stockOverview") },
          { key: "import" as const, label: t("admin.importRecords") },
        ].map((tabItem) => (
          <button
            key={tabItem.key}
            type="button"
            className={cn(
              "px-4 py-2.5 text-sm font-medium border-b-2 transition-colors",
              tab === tabItem.key
                ? "border-primary text-primary"
                : "border-transparent text-muted-foreground hover:text-foreground"
            )}
            onClick={() => setTab(tabItem.key)}
          >
            {tabItem.label}
          </button>
        ))}
      </div>

      {/* Stock Overview Tab */}
      {tab === "stock" && (
        <>
          {/* Product filter */}
          <div className="relative w-fit">
            <select
              className="h-10 appearance-none rounded-lg border border-input bg-background pl-3 pr-8 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              value={filterProductId}
              onChange={(e) => setFilterProductId(e.target.value)}
            >
              <option value="">{t("admin.allProducts")}</option>
              {products.map((p) => (
                <option key={p.id} value={p.id}>{p.title}</option>
              ))}
            </select>
            <ChevronDown className="pointer-events-none absolute right-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          </div>

          <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border bg-muted/30">
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.productName2")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.specLabel")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.totalKeys")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.soldKeys")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.availableKeys")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">锁定</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.invalidKeys")}</th>
                    <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
                  </tr>
                </thead>
                <tbody>
                  {stockList.map((item, idx) => (
                    <tr key={`${item.product_id}-${item.spec_id}-${idx}`} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                      <td className="px-4 py-3 font-medium text-foreground">{item.product_title}</td>
                      <td className="px-4 py-3 text-muted-foreground">{item.spec_name || "-"}</td>
                      <td className="px-4 py-3 text-foreground">{item.total}</td>
                      <td className="px-4 py-3 text-foreground">{item.sold}</td>
                      <td className="px-4 py-3">
                        <span className={cn("font-medium", item.available <= 5 ? "text-amber-500" : "text-foreground")}>
                          {item.available}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className={cn(item.locked > 0 ? "text-amber-500" : "text-foreground")}>
                          {item.locked}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className={cn(item.invalid > 0 ? "text-red-500" : "text-foreground")}>
                          {item.invalid}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center justify-end gap-1">
                          <button
                            type="button"
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
                            title={t("admin.viewDetail")}
                            onClick={() => handleViewDetail(item)}
                          >
                            <Eye className="h-4 w-4" />
                          </button>
                          <button
                            type="button"
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-amber-500/10 hover:text-amber-600 transition-colors disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-muted-foreground"
                            title="批量锁定"
                            onClick={() => openBulkActionModal("lock", item)}
                            disabled={item.available === 0}
                          >
                            <Lock className="h-4 w-4" />
                          </button>
                          <button
                            type="button"
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-emerald-500/10 hover:text-emerald-600 transition-colors disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-muted-foreground"
                            title="批量恢复"
                            onClick={() => openBulkActionModal("unlock", item)}
                            disabled={item.locked === 0}
                          >
                            <Unlock className="h-4 w-4" />
                          </button>
                          <button
                            type="button"
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-destructive/10 hover:text-destructive transition-colors"
                            title={t("admin.batchInvalidate")}
                            onClick={() => setShowInvalidateConfirm(item)}
                          >
                            <Ban className="h-4 w-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                  {stockList.length === 0 && (
                    <tr>
                      <td colSpan={8} className="py-8 text-center text-sm text-muted-foreground">{t("admin.noStockData")}</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}

      {/* Import Records Tab */}
      {tab === "import" && (
        <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border bg-muted/30">
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.batchId")}</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.importCount")}</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.successCount")}</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.failCount")}</th>
                  <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.time")}</th>
                </tr>
              </thead>
              <tbody>
                {importBatches.map((batch) => (
                  <tr key={batch.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                    <td className="px-4 py-3 font-mono text-xs text-foreground">
                      {batch.id.length > 16 ? `${batch.id.slice(0, 8)}...` : batch.id}
                    </td>
                    <td className="px-4 py-3 text-foreground">{batch.total_count}</td>
                    <td className="px-4 py-3 text-emerald-600">{batch.success_count}</td>
                    <td className="px-4 py-3">
                      <span className={cn(batch.fail_count > 0 ? "text-red-500" : "text-foreground")}>
                        {batch.fail_count}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {new Date(batch.created_at).toLocaleString()}
                    </td>
                  </tr>
                ))}
                {importBatches.length === 0 && (
                  <tr>
                    <td colSpan={5} className="py-8 text-center text-sm text-muted-foreground">{t("admin.noImportData")}</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <div className="flex items-center justify-between border-t border-border px-4 py-3">
            <span className="text-sm text-muted-foreground">{t("admin.totalRecords")} {importTotal} {t("admin.records")}</span>
          </div>
        </div>
      )}

      {/* Import Modal */}
      <Modal open={showImportModal} onClose={() => setShowImportModal(false)} className="max-w-lg">
            <div className="border-b border-border px-6 py-4 flex items-center justify-between">
              <h2 className="text-lg font-semibold text-foreground">{t("admin.batchImportKeys")}</h2>
              <button
                type="button"
                onClick={() => setShowImportModal(false)}
                className="rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>
            <div className="flex flex-col gap-4 p-6">
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-foreground">{t("admin.selectProductReq")}</label>
                <select
                  className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  value={importProductId}
                  onChange={(e) => handleProductChange(e.target.value)}
                >
                  <option value="">{t("admin.selectProductPlaceholder")}</option>
                  {products.map((p) => (
                    <option key={p.id} value={p.id}>{p.title}</option>
                  ))}
                </select>
              </div>
              {importProductId && (loadingSpecs ? (
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                  <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                  加载规格...
                </div>
              ) : importSpecs.length > 0 ? (
                <div className="flex flex-col gap-1.5">
                  <label className="text-sm font-medium text-foreground">{t("admin.selectSpec")}</label>
                  <select
                    className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                    value={importSpecId}
                    onChange={(e) => setImportSpecId(e.target.value)}
                  >
                    {importSpecs.map((spec) => (
                      <option key={spec.id} value={spec.id}>{spec.name} — {spec.stock_available} 件库存</option>
                    ))}
                  </select>
                </div>
              ) : null)}
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-foreground">{t("admin.cardKeyContentReq")}</label>
                <textarea
                  className="min-h-32 rounded-lg border border-input bg-background px-3 py-2 text-sm font-mono text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  placeholder={t("admin.cardKeyContentPlaceholder")}
                  value={importContent}
                  onChange={(e) => setImportContent(e.target.value)}
                />
                <p className="text-xs text-muted-foreground">
                  {t("admin.cardKeyContentHint")} {importContent.trim() ? importContent.trim().split("\n").length : 0} {t("admin.cardKeyContentUnit")}
                </p>
              </div>
            </div>
            <div className="flex justify-end gap-3 border-t border-border px-6 py-4">
              <button
                type="button"
                className="rounded-lg border border-input bg-transparent px-4 py-2 text-sm font-medium text-foreground hover:bg-accent transition-colors"
                onClick={() => setShowImportModal(false)}
              >
                {t("admin.cancel")}
              </button>
              <button
                type="button"
                className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
                onClick={handleImport}
                disabled={importing}
              >
                {importing ? t("admin.importing") : t("admin.import")}
              </button>
            </div>
      </Modal>

      {/* Detail Modal */}
      <Modal
        open={showDetailModal}
        onClose={() => {
          setShowDetailModal(false)
          setSelectedDetailKeyIds([])
          setSelectedAction(null)
          setSelectedActionNote("")
        }}
        className="max-w-[90vw] w-[1100px]"
      >
        <div className="border-b border-border px-6 py-4 flex items-center justify-between">
          <div>
            <h2 className="text-lg font-semibold text-foreground">卡密详情</h2>
            {detailItem && (
              <p className="text-sm text-muted-foreground">
                {detailItem.product_title}{detailItem.spec_name ? ` — ${detailItem.spec_name}` : ""}
              </p>
            )}
          </div>
          <button
            type="button"
            onClick={() => {
              setShowDetailModal(false)
              setSelectedDetailKeyIds([])
              setSelectedAction(null)
              setSelectedActionNote("")
            }}
            className="rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
          >
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="p-6">
          {detailLoading ? (
            <div className="flex items-center justify-center py-12">
              <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
            </div>
          ) : detailKeys.length === 0 ? (
            <p className="py-12 text-center text-sm text-muted-foreground">暂无卡密数据</p>
          ) : (
            <>
              <div className="mb-4 flex flex-col gap-3 rounded-lg border border-border bg-muted/20 p-3 sm:flex-row sm:items-center sm:justify-between">
                <div className="flex items-center gap-3">
                  <label className="flex items-center gap-2 text-sm text-foreground">
                    <input
                      type="checkbox"
                      className="h-4 w-4 rounded border-input"
                      checked={selectableDetailKeys.length > 0 && selectedDetailKeyIds.length === selectableDetailKeys.length}
                      onChange={toggleSelectAllDetailKeys}
                    />
                    <span>全选本页可操作项</span>
                  </label>
                  <span className="text-sm text-muted-foreground">已选 {selectedDetailKeyIds.length} 条</span>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <button
                    type="button"
                    className="rounded-lg bg-amber-500/10 px-3 py-1.5 text-xs font-medium text-amber-700 transition-colors hover:bg-amber-500/20 disabled:opacity-40"
                    onClick={() => openSelectedActionModal("lock")}
                    disabled={selectedAvailableKeys.length === 0}
                  >
                    锁定选中可用项（{selectedAvailableKeys.length}）
                  </button>
                  <button
                    type="button"
                    className="rounded-lg bg-emerald-500/10 px-3 py-1.5 text-xs font-medium text-emerald-700 transition-colors hover:bg-emerald-500/20 disabled:opacity-40"
                    onClick={() => openSelectedActionModal("unlock")}
                    disabled={selectedLockedKeys.length === 0}
                  >
                    恢复选中锁定项（{selectedLockedKeys.length}）
                  </button>
                </div>
              </div>
              <div className="overflow-x-auto">
              <table className="w-full text-sm table-fixed">
                <thead>
                  <tr className="border-b border-border bg-muted/30">
                    <th className="w-[4%] px-3 py-2 text-left font-medium text-muted-foreground whitespace-nowrap">选</th>
                    <th className="w-[29%] px-3 py-2 text-left font-medium text-muted-foreground">卡密内容</th>
                    <th className="w-[18%] px-3 py-2 text-left font-medium text-muted-foreground whitespace-nowrap">状态 / 备注</th>
                    <th className="w-[12%] px-3 py-2 text-left font-medium text-muted-foreground whitespace-nowrap">创建时间</th>
                    <th className="w-[10%] px-3 py-2 text-left font-medium text-muted-foreground whitespace-nowrap">订单号</th>
                    <th className="w-[12%] px-3 py-2 text-left font-medium text-muted-foreground whitespace-nowrap">售出时间</th>
                    <th className="w-[15%] px-3 py-2 text-left font-medium text-muted-foreground whitespace-nowrap">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {detailKeys.map((key) => (
                    <tr key={key.id} className="border-b border-border/50 last:border-0">
                      <td className="px-3 py-2 align-top">
                        {key.status === "AVAILABLE" || key.status === "LOCKED" ? (
                          <input
                            type="checkbox"
                            className="mt-0.5 h-4 w-4 rounded border-input"
                            checked={selectedDetailKeyIds.includes(key.id)}
                            onChange={() => toggleDetailSelection(key.id)}
                          />
                        ) : (
                          <span className="text-xs text-muted-foreground">-</span>
                        )}
                      </td>
                      <td className="px-3 py-2 font-mono text-xs text-foreground break-all">{key.content}</td>
                      <td className="px-3 py-2 align-top">
                        <div className="flex flex-col gap-1">
                          <span className={cn(
                            "w-fit rounded-full px-2 py-0.5 text-xs font-medium",
                            key.status === "AVAILABLE" && "bg-emerald-500/10 text-emerald-600",
                            key.status === "SOLD" && "bg-blue-500/10 text-blue-600",
                            key.status === "LOCKED" && "bg-amber-500/10 text-amber-600",
                            key.status === "INVALID" && "bg-red-500/10 text-red-600",
                          )}>
                            {key.status === "AVAILABLE" ? "可用" : key.status === "SOLD" ? "已售" : key.status === "LOCKED" ? "锁定" : "已作废"}
                          </span>
                          {key.lock_note ? (
                            <p className="text-xs text-muted-foreground break-all">
                              {key.lock_note}
                            </p>
                          ) : null}
                        </div>
                      </td>
                      <td className="px-3 py-2 text-xs text-muted-foreground whitespace-nowrap">
                        {new Date(key.created_at).toLocaleString()}
                      </td>
                      <td className="px-3 py-2 font-mono text-xs text-muted-foreground whitespace-nowrap">
                        {key.order_id ? key.order_id.slice(0, 8) + "..." : "-"}
                      </td>
                      <td className="px-3 py-2 text-xs text-muted-foreground whitespace-nowrap">
                        {key.sold_at ? new Date(key.sold_at).toLocaleString() : "-"}
                      </td>
                      <td className="px-3 py-2 whitespace-nowrap">
                        {key.status === "AVAILABLE" || key.status === "LOCKED" ? (
                          <button
                            type="button"
                            className={cn(
                              "rounded-md px-2.5 py-1 text-xs font-medium transition-colors disabled:opacity-50",
                              key.status === "AVAILABLE"
                                ? "bg-amber-500/10 text-amber-700 hover:bg-amber-500/20"
                                : "bg-emerald-500/10 text-emerald-700 hover:bg-emerald-500/20"
                            )}
                            onClick={() => key.status === "AVAILABLE" ? openSingleLockModal(key) : handleToggleKeyStatus(key.id, "AVAILABLE")}
                            disabled={updatingKeyId === key.id || singleLocking}
                          >
                            {updatingKeyId === key.id ? "处理中..." : key.status === "AVAILABLE" ? "锁定" : "恢复"}
                          </button>
                        ) : (
                          <span className="text-xs text-muted-foreground">-</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            </>
          )}
        </div>
        {detailTotal > 20 && (
          <div className="flex items-center justify-between border-t border-border px-6 py-3">
            <span className="text-sm text-muted-foreground">共 {detailTotal} 条</span>
            <div className="flex items-center gap-1">
              <button
                type="button"
                className="rounded-md p-1.5 text-muted-foreground hover:bg-accent disabled:opacity-50"
                disabled={detailPage <= 1}
                onClick={() => handleDetailPageChange(detailPage - 1)}
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              <span className="px-2 text-sm text-foreground">{detailPage} / {Math.ceil(detailTotal / 20)}</span>
              <button
                type="button"
                className="rounded-md p-1.5 text-muted-foreground hover:bg-accent disabled:opacity-50"
                disabled={detailPage >= Math.ceil(detailTotal / 20)}
                onClick={() => handleDetailPageChange(detailPage + 1)}
              >
                <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        )}
      </Modal>

      {/* Single Lock Modal */}
      <Modal
        open={pendingLockKey !== null}
        onClose={() => {
          setPendingLockKey(null)
          setSingleLockNote("")
        }}
        className="max-w-md"
      >
        <div className="flex flex-col gap-4 p-6">
          <div>
            <h3 className="text-base font-semibold text-foreground">锁定卡密</h3>
            {pendingLockKey ? (
              <p className="mt-1 text-sm text-muted-foreground break-all">
                锁定后该卡密将暂时不参与售卖，你可以填写备注方便后续管理。
              </p>
            ) : null}
          </div>
          {pendingLockKey ? (
            <div className="rounded-lg border border-border bg-muted/20 p-3">
              <p className="text-xs text-muted-foreground">当前卡密</p>
              <p className="mt-1 break-all font-mono text-xs text-foreground">{pendingLockKey.content}</p>
            </div>
          ) : null}
          <div className="flex flex-col gap-1.5">
            <label className="text-sm font-medium text-foreground">锁定备注（可选）</label>
            <textarea
              className="min-h-24 rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              placeholder="例如：预留给线下客户 / 暂时自用 / 人工核验中"
              value={singleLockNote}
              onChange={(e) => setSingleLockNote(e.target.value)}
              maxLength={200}
            />
            <p className="text-xs text-muted-foreground">{singleLockNote.trim().length}/200</p>
          </div>
          <div className="flex justify-end gap-3">
            <button
              type="button"
              className="rounded-lg border border-input bg-transparent px-4 py-2 text-sm font-medium text-foreground hover:bg-accent transition-colors"
              onClick={() => {
                setPendingLockKey(null)
                setSingleLockNote("")
              }}
            >
              取消
            </button>
            <button
              type="button"
              className="rounded-lg bg-amber-500 px-4 py-2 text-sm font-medium text-white hover:bg-amber-500/90 transition-colors disabled:opacity-50"
              onClick={handleConfirmSingleLock}
              disabled={singleLocking}
            >
              {singleLocking ? "锁定中..." : "确认锁定"}
            </button>
          </div>
        </div>
      </Modal>

      {/* Selected Action Modal */}
      <Modal
        open={selectedAction !== null}
        onClose={() => {
          setSelectedAction(null)
          setSelectedActionNote("")
        }}
        className="max-w-md"
      >
        <div className="flex flex-col gap-4 p-6">
          <div>
            <h3 className="text-base font-semibold text-foreground">
              {selectedAction?.type === "lock" ? "批量锁定选中卡密" : "批量恢复选中卡密"}
            </h3>
            {selectedAction ? (
              <p className="mt-1 text-sm text-muted-foreground">
                {selectedAction.type === "lock"
                  ? `将锁定你当前勾选的 ${selectedAction.count} 条可用卡密。`
                  : `将恢复你当前勾选的 ${selectedAction.count} 条锁定卡密。`}
              </p>
            ) : null}
          </div>
          {selectedAction?.type === "lock" ? (
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">锁定备注（可选）</label>
              <textarea
                className="min-h-24 rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="例如：从这批里单独抽出来给线下客户"
                value={selectedActionNote}
                onChange={(e) => setSelectedActionNote(e.target.value)}
                maxLength={200}
              />
              <p className="text-xs text-muted-foreground">{selectedActionNote.trim().length}/200</p>
            </div>
          ) : null}
          <div className="flex justify-end gap-3">
            <button
              type="button"
              className="rounded-lg border border-input bg-transparent px-4 py-2 text-sm font-medium text-foreground hover:bg-accent transition-colors"
              onClick={() => {
                setSelectedAction(null)
                setSelectedActionNote("")
              }}
            >
              取消
            </button>
            <button
              type="button"
              className={cn(
                "rounded-lg px-4 py-2 text-sm font-medium text-white transition-colors disabled:opacity-50",
                selectedAction?.type === "lock" ? "bg-amber-500 hover:bg-amber-500/90" : "bg-emerald-600 hover:bg-emerald-600/90"
              )}
              onClick={handleSelectedAction}
              disabled={selectedActionProcessing}
            >
              {selectedActionProcessing ? "处理中..." : selectedAction?.type === "lock" ? "确认锁定选中项" : "确认恢复选中项"}
            </button>
          </div>
        </div>
      </Modal>

      {/* Bulk Lock/Unlock Modal */}
      <Modal
        open={bulkAction !== null}
        onClose={() => {
          setBulkAction(null)
          setBulkLockNote("")
        }}
        className="max-w-md"
      >
        <div className="flex flex-col gap-4 p-6">
          <div>
            <h3 className="text-base font-semibold text-foreground">
              {bulkAction?.type === "lock" ? "批量锁定卡密" : "批量恢复卡密"}
            </h3>
            {bulkAction ? (
              <p className="mt-1 text-sm text-muted-foreground">
                {bulkAction.type === "lock"
                  ? "会把当前商品下所有可用卡密批量转为锁定。"
                  : "会把当前商品下所有已锁定卡密批量恢复为可售状态。"}
              </p>
            ) : null}
          </div>
          {bulkAction ? (
            <div className="rounded-lg border border-border bg-muted/20 p-3 text-sm text-muted-foreground">
              <p className="font-medium text-foreground">
                {bulkAction.item.product_title}{bulkAction.item.spec_name ? ` - ${bulkAction.item.spec_name}` : ""}
              </p>
              <p className="mt-1">
                {bulkAction.type === "lock"
                  ? `本次将锁定 ${bulkAction.item.available} 条可用卡密`
                  : `本次将恢复 ${bulkAction.item.locked} 条锁定卡密`}
              </p>
            </div>
          ) : null}
          {bulkAction?.type === "lock" ? (
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">批量锁定备注（可选）</label>
              <textarea
                className="min-h-24 rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="例如：预留一批给渠道客户 / 暂时下架检查"
                value={bulkLockNote}
                onChange={(e) => setBulkLockNote(e.target.value)}
                maxLength={200}
              />
              <p className="text-xs text-muted-foreground">{bulkLockNote.trim().length}/200</p>
            </div>
          ) : null}
          <div className="flex justify-end gap-3">
            <button
              type="button"
              className="rounded-lg border border-input bg-transparent px-4 py-2 text-sm font-medium text-foreground hover:bg-accent transition-colors"
              onClick={() => {
                setBulkAction(null)
                setBulkLockNote("")
              }}
            >
              取消
            </button>
            <button
              type="button"
              className={cn(
                "rounded-lg px-4 py-2 text-sm font-medium text-white transition-colors disabled:opacity-50",
                bulkAction?.type === "lock" ? "bg-amber-500 hover:bg-amber-500/90" : "bg-emerald-600 hover:bg-emerald-600/90"
              )}
              onClick={handleBulkAction}
              disabled={bulkProcessing}
            >
              {bulkProcessing ? "处理中..." : bulkAction?.type === "lock" ? "确认批量锁定" : "确认批量恢复"}
            </button>
          </div>
        </div>
      </Modal>

      {/* Batch Invalidate Confirmation */}
      <Modal open={showInvalidateConfirm !== null} onClose={() => setShowInvalidateConfirm(null)} className="max-w-md">
        <div className="flex flex-col gap-4 p-6">
          <div className="flex items-start gap-3">
            <div className="rounded-full bg-destructive/10 p-2">
              <AlertCircle className="h-5 w-5 text-destructive" />
            </div>
            <div className="flex-1">
              <h3 className="text-base font-semibold text-foreground">确认批量作废</h3>
              {showInvalidateConfirm && (
                <p className="mt-1 text-sm text-muted-foreground">
                  确定要将「{showInvalidateConfirm.product_title}
                  {showInvalidateConfirm.spec_name ? ` — ${showInvalidateConfirm.spec_name}` : ""}」
                  的 <span className="font-medium text-foreground">{showInvalidateConfirm.available}</span> 条可用卡密全部作废吗？此操作不可撤销。
                </p>
              )}
            </div>
          </div>
          <div className="flex justify-end gap-3">
            <button
              type="button"
              className="rounded-lg border border-input bg-transparent px-4 py-2 text-sm font-medium text-foreground hover:bg-accent transition-colors"
              onClick={() => setShowInvalidateConfirm(null)}
            >
              取消
            </button>
            <button
              type="button"
              className="rounded-lg bg-destructive px-4 py-2 text-sm font-medium text-destructive-foreground hover:bg-destructive/90 transition-colors disabled:opacity-50"
              onClick={handleBatchInvalidate}
              disabled={invalidating}
            >
              {invalidating ? "作废中..." : "确认作废"}
            </button>
          </div>
        </div>
      </Modal>
    </div>
  )
}
