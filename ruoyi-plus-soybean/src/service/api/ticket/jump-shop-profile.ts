import { request } from '@/service/request';

export function fetchGetTicketJumpShopProfileList(params?: Api.Ticket.JumpShopProfileSearchParams) {
  return request<Api.Ticket.JumpShopProfileList>({ url: '/ticket/jump-shop-profile/list', method: 'get', params });
}

export function fetchGetTicketJumpShopProfileOptions() {
  return request<Api.Ticket.JumpShopProfile[]>({ url: '/ticket/jump-shop-profile/options', method: 'get' });
}

export function fetchGetTicketJumpShopProfile(profileId: CommonType.IdType) {
  return request<Api.Ticket.JumpShopProfile>({ url: `/ticket/jump-shop-profile/${profileId}`, method: 'get' });
}

export function fetchCreateTicketJumpShopProfile(data: Api.Ticket.JumpShopProfileOperateParams) {
  return request<boolean>({ url: '/ticket/jump-shop-profile', method: 'post', data });
}

export function fetchUpdateTicketJumpShopProfile(data: Api.Ticket.JumpShopProfileOperateParams) {
  return request<boolean>({ url: '/ticket/jump-shop-profile', method: 'put', data });
}

export function fetchDeleteTicketJumpShopProfile(profileIds: CommonType.IdType[]) {
  return request<boolean>({ url: `/ticket/jump-shop-profile/${profileIds.join(',')}`, method: 'delete' });
}

export function fetchGetJumpShopProductInfo(platformId: CommonType.IdType, productUrl: string) {
  return request<Api.Ticket.JumpShopProductInfo>({
    url: '/ticket/jump-shop/product-info',
    method: 'get',
    params: { platformId, productUrl }
  });
}
