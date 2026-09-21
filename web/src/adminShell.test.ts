import { describe, expect, it } from 'vitest'
import { adminRoutes } from './adminRoutes'

function route(path: string) {
  const found = adminRoutes.find(item => item.path === path)
  if (!found) throw new Error(`missing route: ${path}`)
  return found
}

describe('unified admin routes', () => {
  it('uses the unified shell as the main admin entry', () => {
    const admin = route('/admin')
    const testPlayers = route('/admin/test-players')

    expect(admin.component).toBeDefined()
    expect(admin.meta).toMatchObject({ requiresAuth: true, requiredPermission: 'USER_MANAGE' })
    expect(testPlayers.component).toBeDefined()
    expect(testPlayers.meta).toMatchObject({ requiresAuth: true, requiredPermission: 'USER_MANAGE' })
  })

  it('keeps the old operations page and separated robot permission boundary', () => {
    expect(route('/admin/operations').component).toBeDefined()
    expect(route('/admin/operations').meta).toMatchObject({ requiredPermission: 'USER_MANAGE' })
    expect(route('/admin/users').meta).toMatchObject({ requiredPermission: 'USER_MANAGE' })
    expect(route('/admin/robots').meta).toMatchObject({ requiredPermission: 'ROBOT_READ' })
  })
})
