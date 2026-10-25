import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const pushServiceMock = vi.hoisted(() => ({
  config: vi.fn(),
  subscribe: vi.fn(),
}))

vi.mock('../services/push-service', () => ({ pushService: pushServiceMock }))

import {
  ensureSubscription,
  isStandalone,
  subscriptionInput,
  supportsPush,
  syncPushOnBoot,
  urlBase64ToUint8Array,
} from './push'

// The shared client is browser-only; Node's globals are stubbed per test so no jsdom is needed.
function stubBrowser(options: {
  secure?: boolean
  matchMedia?: boolean
  standalone?: boolean
  notification?: unknown
  serviceWorker?: unknown
  pushManager?: unknown
} = {}) {
  vi.stubGlobal('window', {
    isSecureContext: options.secure ?? true,
    matchMedia: () => ({ matches: options.matchMedia ?? false }),
    ...(options.notification === undefined ? {} : { Notification: options.notification }),
    ...(options.pushManager === undefined ? {} : { PushManager: options.pushManager }),
  })
  vi.stubGlobal('navigator', {
    ...(options.serviceWorker === undefined ? {} : { serviceWorker: options.serviceWorker }),
    ...(options.standalone === undefined ? {} : { standalone: options.standalone }),
  })
}

function fakeRegistration(pushManager: unknown): ServiceWorkerRegistration {
  return {
    update: vi.fn().mockResolvedValue(undefined),
    pushManager,
  } as unknown as ServiceWorkerRegistration
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('urlBase64ToUint8Array', () => {
  it('decodes the URL-safe alphabet without padding', () => {
    // "_-7dzA" exercises both replaced characters and omits the standard "==" padding.
    expect(Array.from(urlBase64ToUint8Array('_-7dzA'))).toEqual([0xff, 0xee, 0xdd, 0xcc])
  })

  it('restores the padding a VAPID key normally omits', () => {
    // Standard Base64 of [1, 2] is "AQI="; browsers hand us "AQI".
    expect(Array.from(urlBase64ToUint8Array('AQI'))).toEqual([1, 2])
  })

  it('still accepts the standard alphabet', () => {
    expect(Array.from(urlBase64ToUint8Array('+/8'))).toEqual([0xfb, 0xff])
  })

  it('returns a view over a plain ArrayBuffer, not a shared buffer', () => {
    const bytes = urlBase64ToUint8Array('AQI')
    expect(bytes.byteOffset).toBe(0)
    expect(bytes.buffer.byteLength).toBe(bytes.length)
  })
})

describe('supportsPush', () => {
  it('requires Notification, serviceWorker and PushManager together', () => {
    stubBrowser({ notification: { permission: 'default' }, serviceWorker: {} })
    expect(supportsPush()).toBe(false)

    stubBrowser({ notification: { permission: 'default' }, serviceWorker: {}, pushManager: class {} })
    expect(supportsPush()).toBe(true)
  })

  it('rejects an insecure origin even when the APIs exist', () => {
    stubBrowser({
      secure: false,
      notification: { permission: 'default' },
      serviceWorker: {},
      pushManager: class {},
    })
    expect(supportsPush()).toBe(false)
  })
})

describe('isStandalone', () => {
  it('accepts the display-mode media query', () => {
    stubBrowser({ matchMedia: true })
    expect(isStandalone()).toBe(true)
  })

  it('falls back to the iOS navigator.standalone flag', () => {
    stubBrowser({ matchMedia: false, standalone: true })
    expect(isStandalone()).toBe(true)
  })

  it('rejects a normal Safari tab', () => {
    stubBrowser({ matchMedia: false, standalone: false })
    expect(isStandalone()).toBe(false)
  })
})

describe('ensureSubscription', () => {
  it('reuses a subscription created with the current VAPID key', async () => {
    const existing = {
      options: { applicationServerKey: urlBase64ToUint8Array('AQI').buffer },
    }
    const subscribe = vi.fn()
    const registration = fakeRegistration({
      getSubscription: vi.fn().mockResolvedValue(existing),
      subscribe,
    })

    const result = await ensureSubscription(registration, 'AQI')

    expect(result).toBe(existing)
    expect(subscribe).not.toHaveBeenCalled()
  })

  it('replaces a subscription bound to an old key', async () => {
    const existing = {
      options: { applicationServerKey: new Uint8Array([9, 9]).buffer },
      unsubscribe: vi.fn().mockResolvedValue(true),
    }
    const fresh = { endpoint: 'https://web.push.apple.com/fresh' }
    const subscribe = vi.fn().mockResolvedValue(fresh)
    const registration = fakeRegistration({
      getSubscription: vi.fn().mockResolvedValue(existing),
      subscribe,
    })

    const result = await ensureSubscription(registration, 'AQI')

    expect(existing.unsubscribe).toHaveBeenCalledTimes(1)
    expect(result).toBe(fresh)
    const options = subscribe.mock.calls[0][0] as PushSubscriptionOptionsInit
    expect(Array.from(options.applicationServerKey as Uint8Array)).toEqual([1, 2])
  })

  it('keeps the current subscription when the server returns a malformed key', async () => {
    const existing = {
      options: { applicationServerKey: new Uint8Array([1, 2]).buffer },
      unsubscribe: vi.fn().mockResolvedValue(true),
    }
    const subscribe = vi.fn()
    const registration = fakeRegistration({
      getSubscription: vi.fn().mockResolvedValue(existing),
      subscribe,
    })

    await expect(ensureSubscription(registration, 'not base64!')).rejects.toThrow()
    expect(existing.unsubscribe).not.toHaveBeenCalled()
    expect(subscribe).not.toHaveBeenCalled()
  })
})

describe('syncPushOnBoot', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('does nothing on an insecure origin', async () => {
    stubBrowser({
      secure: false,
      notification: { permission: 'granted' },
      serviceWorker: {},
      pushManager: class {},
    })
    vi.stubGlobal('Notification', { permission: 'granted' })

    await syncPushOnBoot('user-insecure')

    expect(pushServiceMock.config).not.toHaveBeenCalled()
  })

  it('neither creates a subscription nor prompts when the device has none', async () => {
    const subscribe = vi.fn()
    stubBrowser({
      notification: { permission: 'granted' },
      serviceWorker: { register: vi.fn().mockResolvedValue(fakeRegistration({
        getSubscription: vi.fn().mockResolvedValue(null),
        subscribe,
      })) },
      pushManager: class {},
    })
    vi.stubGlobal('Notification', { permission: 'granted' })
    pushServiceMock.config.mockResolvedValue({ enabled: true, publicKey: 'AQI', reason: null })

    await syncPushOnBoot('user-no-device')

    expect(subscribe).not.toHaveBeenCalled()
    expect(pushServiceMock.subscribe).not.toHaveBeenCalled()
  })
})

describe('subscriptionInput', () => {
  it('maps the browser JSON onto the server payload', () => {
    const subscription = {
      toJSON: () => ({ endpoint: 'https://web.push.apple.com/x', keys: { p256dh: 'p', auth: 'a' } }),
    } as unknown as PushSubscription

    expect(subscriptionInput(subscription)).toEqual({
      endpoint: 'https://web.push.apple.com/x',
      keys: { p256dh: 'p', auth: 'a' },
    })
  })

  it('refuses a subscription without keys', () => {
    const subscription = {
      toJSON: () => ({ endpoint: 'https://web.push.apple.com/x' }),
    } as unknown as PushSubscription

    expect(() => subscriptionInput(subscription)).toThrow('订阅缺少推送密钥')
  })
})
