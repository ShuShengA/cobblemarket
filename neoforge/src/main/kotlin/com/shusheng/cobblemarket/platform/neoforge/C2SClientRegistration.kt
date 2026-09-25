package com.shusheng.cobblemarket.platform.neoforge

import com.shusheng.cobblemarket.permission.RequestPermissionSnapshotPayload
import com.shusheng.cobblemarket.permission.RequestPermissionListPayload
import com.shusheng.cobblemarket.permission.SavePlayerPermissionsPayload
import com.shusheng.cobblemarket.permission.RemovePlayerPermissionsPayload
import com.shusheng.cobblemarket.permission.RequestPermissionAuditPayload
import com.shusheng.cobblemarket.network.RequestSpeciesRestPayload
import com.shusheng.cobblemarket.network.AcceptPendingDeliverPayload
import com.shusheng.cobblemarket.network.AddHeldItemBlacklistPayload
import com.shusheng.cobblemarket.network.AddHeldItemPriceLimitPayload
import com.shusheng.cobblemarket.network.AddItemBlacklistPayload
import com.shusheng.cobblemarket.network.AddItemPriceLimitPayload
import com.shusheng.cobblemarket.network.AddItemsBlacklistPayload
import com.shusheng.cobblemarket.network.AddPokemonBlacklistPayload
import com.shusheng.cobblemarket.network.AddPokemonPriceLimitPayload
import com.shusheng.cobblemarket.network.AdminBanPayload
import com.shusheng.cobblemarket.network.AdminCancelItemPayload
import com.shusheng.cobblemarket.network.AdminCancelPokemonPayload
import com.shusheng.cobblemarket.network.AdminRequestItemPayload
import com.shusheng.cobblemarket.network.AdminRequestPokemonPayload
import com.shusheng.cobblemarket.network.AdminUnbanPayload
import com.shusheng.cobblemarket.network.BuyFromMarketPayload
import com.shusheng.cobblemarket.network.BuyItemPayload
import com.shusheng.cobblemarket.network.BuyPointMarketEntryPayload
import com.shusheng.cobblemarket.network.CancelBuyOrderPayload
import com.shusheng.cobblemarket.network.CancelFromMarketPayload
import com.shusheng.cobblemarket.network.CancelItemPayload
import com.shusheng.cobblemarket.network.CancelPointOrderPayload
import com.shusheng.cobblemarket.network.ClaimItemReturnPayload
import com.shusheng.cobblemarket.network.ClaimPokemonReturnPayload
import com.shusheng.cobblemarket.network.CollectBalancePayload
import com.shusheng.cobblemarket.network.ConfirmPointOrderPayload
import com.shusheng.cobblemarket.network.CreateItemAuctionPayload
import com.shusheng.cobblemarket.network.CreateItemBuyOrderPayload
import com.shusheng.cobblemarket.network.CreatePokemonAuctionPayload
import com.shusheng.cobblemarket.network.CreatePointOrderPayload
import com.shusheng.cobblemarket.network.CreatePokemonBuyOrderPayload
import com.shusheng.cobblemarket.network.DeliverItemBuyOrderPayload
import com.shusheng.cobblemarket.network.DeliverPokemonBuyOrderPayload
import com.shusheng.cobblemarket.network.ForceCancelAuctionPayload
import com.shusheng.cobblemarket.network.ForceCancelBuyOrderPayload
import com.shusheng.cobblemarket.network.MarkPointOrderPaidPayload
import com.shusheng.cobblemarket.network.PlaceBidPayload
import com.shusheng.cobblemarket.network.CreatePointCodesPayload
import com.shusheng.cobblemarket.network.RedeemPointCodePayload
import com.shusheng.cobblemarket.network.PurgePointCodesPayload
import com.shusheng.cobblemarket.network.RequestPointCodesPayload
import com.shusheng.cobblemarket.network.RequestPointRecordsPayload
import com.shusheng.cobblemarket.network.RevokePointCodePayload
import com.shusheng.cobblemarket.network.RejectPendingDeliverPayload
import com.shusheng.cobblemarket.network.RejectPointOrderPayload
import com.shusheng.cobblemarket.network.RemoveItemBlacklistPayload
import com.shusheng.cobblemarket.network.RemoveItemPriceLimitPayload
import com.shusheng.cobblemarket.network.RemoveItemsBlacklistPayload
import com.shusheng.cobblemarket.network.RemovePointMarketEntryPayload
import com.shusheng.cobblemarket.network.RemovePokemonBlacklistPayload
import com.shusheng.cobblemarket.network.RemovePokemonPriceLimitPayload
import com.shusheng.cobblemarket.network.RequestAuctionDurationsPayload
import com.shusheng.cobblemarket.network.RequestAuctionListPayload
import com.shusheng.cobblemarket.network.RequestBalancePayload
import com.shusheng.cobblemarket.network.RequestBanListPayload
import com.shusheng.cobblemarket.network.RequestBuyOrderListPayload
import com.shusheng.cobblemarket.network.RequestCreditInfoPayload
import com.shusheng.cobblemarket.network.RequestDepositInfoPayload
import com.shusheng.cobblemarket.network.RequestDepositPayload
import com.shusheng.cobblemarket.network.RequestFinanceStatsPayload
import com.shusheng.cobblemarket.network.RequestHistoryPayload
import com.shusheng.cobblemarket.network.RequestItemBlacklistPayload
import com.shusheng.cobblemarket.network.RequestItemMarketPayload
import com.shusheng.cobblemarket.network.RequestItemPriceLimitPayload
import com.shusheng.cobblemarket.network.RequestItemReturnPayload
import com.shusheng.cobblemarket.network.RequestLoanHistoryPayload
import com.shusheng.cobblemarket.network.RequestLoanPayload
import com.shusheng.cobblemarket.network.RequestMarketPayload
import com.shusheng.cobblemarket.network.RequestMyPokemonPayload
import com.shusheng.cobblemarket.network.RequestObjectPreviewPayload
import com.shusheng.cobblemarket.network.RequestPayQrPayload
import com.shusheng.cobblemarket.network.RequestPlayerNameSuggestionsPayload
import com.shusheng.cobblemarket.network.RequestPointMarketPayload
import com.shusheng.cobblemarket.network.RequestPointsConfigPayload
import com.shusheng.cobblemarket.network.RequestPointOrdersPayload
import com.shusheng.cobblemarket.network.RequestPointsShopPayload
import com.shusheng.cobblemarket.network.RequestPurpleCardApplyInfoPayload
import com.shusheng.cobblemarket.network.RequestPurpleCardApplyPayload
import com.shusheng.cobblemarket.network.RequestPurpleCardRedoPayload
import com.shusheng.cobblemarket.network.RequestBlackCardApplyInfoPayload
import com.shusheng.cobblemarket.network.RequestBlackCardApplyPayload
import com.shusheng.cobblemarket.network.RequestBlackCardRedoPayload
import com.shusheng.cobblemarket.network.RequestCardHolderBoardPayload
import com.shusheng.cobblemarket.network.RequestCardHolderListPayload
import com.shusheng.cobblemarket.network.RequestCardRevokePayload
import com.shusheng.cobblemarket.network.RequestPokemonBlacklistPayload
import com.shusheng.cobblemarket.network.RequestPokemonPriceLimitPayload
import com.shusheng.cobblemarket.network.RequestPokemonReturnPayload
import com.shusheng.cobblemarket.network.RequestRepayListPayload
import com.shusheng.cobblemarket.network.RequestRepayPayload
import com.shusheng.cobblemarket.network.RequestRevokeBadDebtPayload
import com.shusheng.cobblemarket.network.RequestServerConfigPayload
import com.shusheng.cobblemarket.network.RequestTierIconPayload
import com.shusheng.cobblemarket.network.RequestWithdrawPayload
import com.shusheng.cobblemarket.network.SavePointMarketEntryPayload
import com.shusheng.cobblemarket.network.UpdatePointMarketStockPayload
import com.shusheng.cobblemarket.network.SavePointsConfigPayload
import com.shusheng.cobblemarket.network.SaveServerConfigPayload
import com.shusheng.cobblemarket.network.SellFromStoragePayload
import com.shusheng.cobblemarket.network.SellItemPayload
import com.shusheng.cobblemarket.network.DeleteShopSnapshotPayload
import com.shusheng.cobblemarket.network.RequestShopSnapshotsPayload
import com.shusheng.cobblemarket.network.SaveShopSnapshotPayload
import com.shusheng.cobblemarket.network.SetMarketEnabledPayload
import com.shusheng.cobblemarket.network.SharePayload
import net.minecraft.network.PacketByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.PayloadRegistrar

/**
 * neoforge 网络协商要求双端注册集合完全一致（id + 流向）：
 * 任何一端缺少另一端注册的非 optional payload，连接会被拒绝（NetworkComponentNegotiator）。
 *
 * common 里的 C2S payload 由服务端（各 Network.register）注册 playToServer + 业务 handler；
 * 客户端也必须空注册同名 playToServer 类型，否则连不上 neoforge 服务器。
 * （fabric 无此机制：客户端发送前不校验本地注册，仅 neoforge 需要。）
 *
 * ⚠ 新增 C2S payload 时必须同步本清单——漏加会在进服协商时直接断连（fail-fast）。
 */

fun registerClientC2S() {
    NeoForgePlatform.modEventBus().addListener(RegisterPayloadHandlersEvent::class.java) { event ->
        val registrar = event.registrar(PAYLOAD_VERSION)
        c2s(registrar, AcceptPendingDeliverPayload.ID, AcceptPendingDeliverPayload.CODEC)
        c2s(registrar, AddHeldItemBlacklistPayload.ID, AddHeldItemBlacklistPayload.CODEC)
        c2s(registrar, AddHeldItemPriceLimitPayload.ID, AddHeldItemPriceLimitPayload.CODEC)
        c2s(registrar, AddItemBlacklistPayload.ID, AddItemBlacklistPayload.CODEC)
        c2s(registrar, AddItemPriceLimitPayload.ID, AddItemPriceLimitPayload.CODEC)
        c2s(registrar, AddItemsBlacklistPayload.ID, AddItemsBlacklistPayload.CODEC)
        c2s(registrar, AddPokemonBlacklistPayload.ID, AddPokemonBlacklistPayload.CODEC)
        c2s(registrar, AddPokemonPriceLimitPayload.ID, AddPokemonPriceLimitPayload.CODEC)
        c2s(registrar, AdminBanPayload.ID, AdminBanPayload.CODEC)
        c2s(registrar, AdminCancelItemPayload.ID, AdminCancelItemPayload.CODEC)
        c2s(registrar, AdminCancelPokemonPayload.ID, AdminCancelPokemonPayload.CODEC)
        c2s(registrar, AdminRequestItemPayload.ID, AdminRequestItemPayload.CODEC)
        c2s(registrar, AdminRequestPokemonPayload.ID, AdminRequestPokemonPayload.CODEC)
        c2s(registrar, AdminUnbanPayload.ID, AdminUnbanPayload.CODEC)
        c2s(registrar, BuyFromMarketPayload.ID, BuyFromMarketPayload.CODEC)
        c2s(registrar, BuyItemPayload.ID, BuyItemPayload.CODEC)
        c2s(registrar, CancelBuyOrderPayload.ID, CancelBuyOrderPayload.CODEC)
        c2s(registrar, CancelFromMarketPayload.ID, CancelFromMarketPayload.CODEC)
        c2s(registrar, CancelItemPayload.ID, CancelItemPayload.CODEC)
        c2s(registrar, CancelPointOrderPayload.ID, CancelPointOrderPayload.CODEC)
        c2s(registrar, ClaimItemReturnPayload.ID, ClaimItemReturnPayload.CODEC)
        c2s(registrar, ClaimPokemonReturnPayload.ID, ClaimPokemonReturnPayload.CODEC)
        c2s(registrar, CollectBalancePayload.ID, CollectBalancePayload.CODEC)
        c2s(registrar, ConfirmPointOrderPayload.ID, ConfirmPointOrderPayload.CODEC)
        c2s(registrar, CreateItemAuctionPayload.ID, CreateItemAuctionPayload.CODEC)
        c2s(registrar, CreateItemBuyOrderPayload.ID, CreateItemBuyOrderPayload.CODEC)
        c2s(registrar, CreatePokemonAuctionPayload.ID, CreatePokemonAuctionPayload.CODEC)
        c2s(registrar, CreatePokemonBuyOrderPayload.ID, CreatePokemonBuyOrderPayload.CODEC)
        c2s(registrar, CreatePointOrderPayload.ID, CreatePointOrderPayload.CODEC)
        c2s(registrar, DeliverItemBuyOrderPayload.ID, DeliverItemBuyOrderPayload.CODEC)
        c2s(registrar, DeliverPokemonBuyOrderPayload.ID, DeliverPokemonBuyOrderPayload.CODEC)
        c2s(registrar, ForceCancelAuctionPayload.ID, ForceCancelAuctionPayload.CODEC)
        c2s(registrar, ForceCancelBuyOrderPayload.ID, ForceCancelBuyOrderPayload.CODEC)
        c2s(registrar, MarkPointOrderPaidPayload.ID, MarkPointOrderPaidPayload.CODEC)
        c2s(registrar, PlaceBidPayload.ID, PlaceBidPayload.CODEC)
        c2s(registrar, RedeemPointCodePayload.ID, RedeemPointCodePayload.CODEC)
        // ⚠ 兑换码管理四个包 + 玩家侧记录包：NeoForge 必须在这里登记，漏了会在协商阶段直接断连（方案风险清单第 5 条）
        c2s(registrar, CreatePointCodesPayload.ID, CreatePointCodesPayload.CODEC)
        c2s(registrar, PurgePointCodesPayload.ID, PurgePointCodesPayload.CODEC)
        c2s(registrar, RequestPointCodesPayload.ID, RequestPointCodesPayload.CODEC)
        c2s(registrar, RevokePointCodePayload.ID, RevokePointCodePayload.CODEC)
        c2s(registrar, RequestPointRecordsPayload.ID, RequestPointRecordsPayload.CODEC)
        // RP 商城（商品区）四个包：同上，漏登记会在协商阶段直接断连
        c2s(registrar, RequestPointMarketPayload.ID, RequestPointMarketPayload.CODEC)
        c2s(registrar, SavePointMarketEntryPayload.ID, SavePointMarketEntryPayload.CODEC)
        c2s(registrar, RemovePointMarketEntryPayload.ID, RemovePointMarketEntryPayload.CODEC)
        c2s(registrar, UpdatePointMarketStockPayload.ID, UpdatePointMarketStockPayload.CODEC)
        c2s(registrar, BuyPointMarketEntryPayload.ID, BuyPointMarketEntryPayload.CODEC)
        // 配方快照的三个（请求列表 / 存 / 删）
        c2s(registrar, RequestShopSnapshotsPayload.ID, RequestShopSnapshotsPayload.CODEC)
        c2s(registrar, SaveShopSnapshotPayload.ID, SaveShopSnapshotPayload.CODEC)
        c2s(registrar, DeleteShopSnapshotPayload.ID, DeleteShopSnapshotPayload.CODEC)
        c2s(registrar, RejectPendingDeliverPayload.ID, RejectPendingDeliverPayload.CODEC)
        c2s(registrar, RemoveItemBlacklistPayload.ID, RemoveItemBlacklistPayload.CODEC)
        c2s(registrar, RemoveItemPriceLimitPayload.ID, RemoveItemPriceLimitPayload.CODEC)
        c2s(registrar, RemoveItemsBlacklistPayload.ID, RemoveItemsBlacklistPayload.CODEC)
        c2s(registrar, RemovePokemonBlacklistPayload.ID, RemovePokemonBlacklistPayload.CODEC)
        c2s(registrar, RemovePokemonPriceLimitPayload.ID, RemovePokemonPriceLimitPayload.CODEC)
        c2s(registrar, RequestAuctionDurationsPayload.ID, RequestAuctionDurationsPayload.CODEC)
        c2s(registrar, RequestAuctionListPayload.ID, RequestAuctionListPayload.CODEC)
        c2s(registrar, RequestBalancePayload.ID, RequestBalancePayload.CODEC)
        c2s(registrar, RequestBanListPayload.ID, RequestBanListPayload.CODEC)
        c2s(registrar, RequestBuyOrderListPayload.ID, RequestBuyOrderListPayload.CODEC)
        c2s(registrar, RequestCreditInfoPayload.ID, RequestCreditInfoPayload.CODEC)
        c2s(registrar, RequestDepositInfoPayload.ID, RequestDepositInfoPayload.CODEC)
        c2s(registrar, RequestDepositPayload.ID, RequestDepositPayload.CODEC)
        c2s(registrar, RequestFinanceStatsPayload.ID, RequestFinanceStatsPayload.CODEC)
        c2s(registrar, RequestHistoryPayload.ID, RequestHistoryPayload.CODEC)
        c2s(registrar, RequestItemBlacklistPayload.ID, RequestItemBlacklistPayload.CODEC)
        c2s(registrar, RequestItemMarketPayload.ID, RequestItemMarketPayload.CODEC)
        c2s(registrar, RequestItemPriceLimitPayload.ID, RequestItemPriceLimitPayload.CODEC)
        c2s(registrar, RequestItemReturnPayload.ID, RequestItemReturnPayload.CODEC)
        c2s(registrar, RequestLoanHistoryPayload.ID, RequestLoanHistoryPayload.CODEC)
        c2s(registrar, RequestLoanPayload.ID, RequestLoanPayload.CODEC)
        c2s(registrar, RequestMarketPayload.ID, RequestMarketPayload.CODEC)
        c2s(registrar, RequestMyPokemonPayload.ID, RequestMyPokemonPayload.CODEC)
        c2s(registrar, RequestObjectPreviewPayload.ID, RequestObjectPreviewPayload.CODEC)
        c2s(registrar, RequestPayQrPayload.ID, RequestPayQrPayload.CODEC)
        c2s(registrar, RequestPlayerNameSuggestionsPayload.ID, RequestPlayerNameSuggestionsPayload.CODEC)
        c2s(registrar, RequestPointsConfigPayload.ID, RequestPointsConfigPayload.CODEC)
        c2s(registrar, RequestPointsShopPayload.ID, RequestPointsShopPayload.CODEC)
        c2s(registrar, RejectPointOrderPayload.ID, RejectPointOrderPayload.CODEC)
        c2s(registrar, RequestPointOrdersPayload.ID, RequestPointOrdersPayload.CODEC)
        c2s(registrar, RequestTierIconPayload.ID, RequestTierIconPayload.CODEC)
        c2s(registrar, RequestPurpleCardApplyInfoPayload.ID, RequestPurpleCardApplyInfoPayload.CODEC)
        c2s(registrar, RequestPurpleCardApplyPayload.ID, RequestPurpleCardApplyPayload.CODEC)
        c2s(registrar, RequestPurpleCardRedoPayload.ID, RequestPurpleCardRedoPayload.CODEC)
        c2s(registrar, RequestBlackCardApplyInfoPayload.ID, RequestBlackCardApplyInfoPayload.CODEC)
        c2s(registrar, RequestBlackCardApplyPayload.ID, RequestBlackCardApplyPayload.CODEC)
        c2s(registrar, RequestBlackCardRedoPayload.ID, RequestBlackCardRedoPayload.CODEC)
        c2s(registrar, RequestCardHolderBoardPayload.ID, RequestCardHolderBoardPayload.CODEC)
        c2s(registrar, RequestCardHolderListPayload.ID, RequestCardHolderListPayload.CODEC)
        c2s(registrar, RequestCardRevokePayload.ID, RequestCardRevokePayload.CODEC)
        c2s(registrar, RequestPokemonBlacklistPayload.ID, RequestPokemonBlacklistPayload.CODEC)
        c2s(registrar, RequestPokemonPriceLimitPayload.ID, RequestPokemonPriceLimitPayload.CODEC)
        c2s(registrar, RequestPokemonReturnPayload.ID, RequestPokemonReturnPayload.CODEC)
        c2s(registrar, RequestRepayListPayload.ID, RequestRepayListPayload.CODEC)
        c2s(registrar, RequestRepayPayload.ID, RequestRepayPayload.CODEC)
        c2s(registrar, RequestRevokeBadDebtPayload.ID, RequestRevokeBadDebtPayload.CODEC)
        c2s(registrar, RequestServerConfigPayload.ID, RequestServerConfigPayload.CODEC)
        c2s(registrar, RequestWithdrawPayload.ID, RequestWithdrawPayload.CODEC)
        c2s(registrar, SavePointsConfigPayload.ID, SavePointsConfigPayload.CODEC)
        c2s(registrar, SaveServerConfigPayload.ID, SaveServerConfigPayload.CODEC)
        c2s(registrar, SellFromStoragePayload.ID, SellFromStoragePayload.CODEC)
        c2s(registrar, SellItemPayload.ID, SellItemPayload.CODEC)
        c2s(registrar, SetMarketEnabledPayload.ID, SetMarketEnabledPayload.CODEC)
        c2s(registrar, SharePayload.ID, SharePayload.CODEC)

        // 管理权限节点（2026-09-12 S1）：权限授予是服主专属，与其余管理权限分开
        c2s(registrar, RequestPermissionSnapshotPayload.ID, RequestPermissionSnapshotPayload.CODEC)
        c2s(registrar, RequestPermissionListPayload.ID, RequestPermissionListPayload.CODEC)
        c2s(registrar, SavePlayerPermissionsPayload.ID, SavePlayerPermissionsPayload.CODEC)
        c2s(registrar, RemovePlayerPermissionsPayload.ID, RemovePlayerPermissionsPayload.CODEC)
        c2s(registrar, RequestPermissionAuditPayload.ID, RequestPermissionAuditPayload.CODEC)
        c2s(registrar, RequestSpeciesRestPayload.ID, RequestSpeciesRestPayload.CODEC)
    }
}

private fun <T : CustomPayload> c2s(
    registrar: PayloadRegistrar,
    id: CustomPayload.Id<T>,
    codec: PacketCodec<in PacketByteBuf, T>,
) {
    // 单机时服务端已注册同 id（业务 handler，HIGH），空注册跳过；专用客户端正常注册
    if (NeoForgePlatform.markPayloadRegistered(id.id(), DIR_C2S)) {
        registrar.playToServer(id, codec) { _, _ -> }
    }
}
