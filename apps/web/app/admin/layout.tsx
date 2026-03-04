import React from "react"
import type { Metadata } from "next"
import { AdminLayoutShell } from "@/components/layout/admin-layout-shell"
import { getSiteConfig } from "@/services/api-server"

export async function generateMetadata(): Promise<Metadata> {
  try {
    const config = await getSiteConfig()
    const siteName = config.site_name?.trim()
    return {
      title: siteName ? `${siteName} 后台管理` : "后台管理",
    }
  } catch {
    return {
      title: "后台管理",
    }
  }
}

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return <AdminLayoutShell>{children}</AdminLayoutShell>
}
