package dev.kizuna.meridian.healing.fabric.mixin;

import dev.kizuna.meridian.healing.TemporaryHealthState;
import dev.kizuna.meridian.healing.fabric.HealingEntityAccess;
import dev.kizuna.meridian.healing.fabric.HealingFabric;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityHealingMixin implements HealingEntityAccess {
    @Unique private static final int MERIDIAN_SYNC_TICKS = 20;
    @Unique private TemporaryHealthState meridian$effects = new TemporaryHealthState();
    @Unique private boolean meridian$internalHealth;
    @Unique private boolean meridian$loading;
    @Unique private boolean meridian$refreshPending = true;
    @Unique private boolean meridian$healthPaused;
    @Unique private boolean meridian$failureReported;

    @Override
    public boolean meridian$refreshPending() {
        // 存档和实体入世后的首次刷新不等待整秒边界。
        return meridian$refreshPending;
    }

    @Override
    public void meridian$markRefreshPending() {
        // 这里只记录实体本地意图，不查询身体宿主。
        meridian$refreshPending = true;
    }

    @Override
    public void meridian$clearRefreshPending() {
        // 仅在服务端主线程已完成归属检查后确认本次刷新。
        meridian$refreshPending = false;
    }

    @Override
    public boolean meridian$healthPaused() {
        // 暂停状态跟随当前实体连接，重新登录使用新的实体重新认领。
        return meridian$healthPaused;
    }

    @Override
    public void meridian$pauseHealth() {
        // 已暂停的身体封住原版治疗旁路，并停止后续宿主查询。
        meridian$healthPaused = true;
    }

    @Override
    public boolean meridian$failureReported() {
        // 同一实体的同步故障只报告一次，避免每 tick 输出堆栈。
        return meridian$failureReported;
    }

    @Override
    public void meridian$markFailureReported() {
        // 错误记录归实体所有，不在全局表中保留已卸载实体。
        meridian$failureReported = true;
    }

    @Override
    public TemporaryHealthState meridian$effects() {
        // 效果归实体持有，死亡、卸载后不会留下全局强引用。
        return meridian$effects;
    }

    @Override
    public boolean meridian$internalHealth() {
        // 身体血条投影与构造器初始赋值使用内部 setter，其余调用均进入宿主校验。
        return meridian$internalHealth;
    }

    @Override
    public void meridian$internalHealth(boolean internal) {
        // 调用方必须在 finally 中恢复标志，异常不能留下永久旁路。
        meridian$internalHealth = internal;
    }

    @Override
    public boolean meridian$loadingEffects() {
        // 读取 NBT 时暂缓效果授予，待保存的局部余额一起恢复。
        return meridian$loading;
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;setHealth(F)V"))
    private void meridian$initializeHealth(LivingEntity entity, float health) {
        // 区块生成线程也会构造生物；此时只初始化实体血条，尚未进入身体宿主的运行期。
        meridian$internalHealth = true;
        try {
            entity.setHealth(health);
        } finally {
            // 仅构造器这次初始赋值可直达原版，之后的 setter 仍必须经过权威身体校验。
            meridian$internalHealth = false;
        }
    }

    @Inject(method = "heal", at = @At("HEAD"), cancellable = true)
    private void meridian$healBody(float amount, CallbackInfo callback) {
        // 覆盖药水、信标及其他模组对公共 heal 的调用，未绑定实体继续原版。
        if (HealingFabric.interceptHeal((LivingEntity) (Object) this, amount)) {
            callback.cancel();
        }
    }

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void meridian$projectHealth(float health, CallbackInfo callback) {
        // 杜绝绕过 heal 的直接回血；血条只接受身体权威 HP 的投影。
        if (HealingFabric.interceptSetHealth((LivingEntity) (Object) this)) {
            callback.cancel();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void meridian$syncHealth(CallbackInfo callback) {
        // 展示每秒对齐一次，治疗、伤害和效果变更另有即时更新入口。
        LivingEntity entity = (LivingEntity) (Object) this;
        if (meridian$refreshPending || entity.age % MERIDIAN_SYNC_TICKS == 0) {
            HealingFabric.refresh(entity);
        }
    }

    @Inject(method = "onStatusEffectApplied", at = @At("TAIL"))
    private void meridian$boostApplied(StatusEffectInstance effect, Entity source, CallbackInfo callback) {
        // 等效果进入原版效果表再更新身体上限，避免晚一秒才显示新上限。
        if (effect.getEffectType() == StatusEffects.HEALTH_BOOST) {
            HealingFabric.refresh((LivingEntity) (Object) this);
        }
    }

    @Inject(method = "onStatusEffectUpgraded", at = @At("TAIL"))
    private void meridian$boostUpgraded(StatusEffectInstance effect, boolean reapply, Entity source, CallbackInfo callback) {
        // 同级刷新与升级共用本附属增量记录，不重复叠加上限。
        if (effect.getEffectType() == StatusEffects.HEALTH_BOOST) {
            HealingFabric.refresh((LivingEntity) (Object) this);
        }
    }

    @Inject(method = "onStatusEffectRemoved", at = @At("TAIL"))
    private void meridian$boostRemoved(StatusEffectInstance effect, CallbackInfo callback) {
        // 移除通知发生在效果表更新后，立即撤销上限并裁剪超额 HP。
        if (effect.getEffectType() == StatusEffects.HEALTH_BOOST) {
            HealingFabric.refresh((LivingEntity) (Object) this);
        }
    }

    @Inject(method = "damage", at = @At("HEAD"))
    private void meridian$enterDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        // 为固定命中后的可信集成提供同步实体上下文。
        HealingFabric.beginDamage((LivingEntity) (Object) this);
    }

    @Inject(method = "damage", at = @At("RETURN"))
    private void meridian$leaveDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        // 包括无敌帧和盾牌拒绝的早退路径，均释放上下文。
        HealingFabric.endDamage((LivingEntity) (Object) this);
    }

    @Redirect(method = "applyDamage", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;getAbsorptionAmount()F"))
    private float meridian$skipGlobalShield(LivingEntity entity) {
        // 已绑定实体的黄心由命中部位结算，原版阶段必须看见零全局余额。
        if (HealingFabric.blocksVanillaHealth(entity)) {
            return 0;
        }
        return entity.getAbsorptionAmount();
    }

    @Redirect(method = "applyDamage", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;setAbsorptionAmount(F)V"))
    private void meridian$preserveLocalShield(LivingEntity entity, float amount) {
        // 原版 applyDamage 有多处全局扣黄心，均不能改写局部汇总显示。
        if (!HealingFabric.blocksVanillaHealth(entity)) {
            entity.setAbsorptionAmount(amount);
        }
    }

    @Inject(method = "tryUseTotem", at = @At("HEAD"), cancellable = true)
    private void meridian$preventLateTotem(DamageSource source, CallbackInfoReturnable<Boolean> callback) {
        // 身体已死亡说明此次没有批准保命，不能事后只复活原版血条。
        LivingEntity entity = (LivingEntity) (Object) this;
        try {
            var binding = HealingFabric.inspect(entity);
            if (binding.isPresent() && binding.get().body().getVitality().getDead()) {
                callback.setReturnValue(false);
            }
        } catch (RuntimeException failure) {
            // 未确认身体允许保命时停止图腾效果，不能消费物品或只复活原版血条。
            HealingFabric.report(entity, failure);
            callback.setReturnValue(false);
        }
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("HEAD"))
    private void meridian$beginRead(NbtCompound nbt, CallbackInfo callback) {
        // 原版会先加载全局黄心及药效；此期间不重新授予局部余额。
        meridian$loading = true;
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void meridian$readEffects(NbtCompound nbt, CallbackInfo callback) {
        // 保存过的零余额同样恢复，不能因重连重置黄心。
        try {
            if (nbt.contains("MeridianHealingEffects")) {
                meridian$effects.decode(nbt.getByteArray("MeridianHealingEffects"));
            }
        } finally {
            meridian$loading = false;
        }
        meridian$markRefreshPending();
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void meridian$writeEffects(NbtCompound nbt, CallbackInfo callback) {
        // 保存 protoc 效果记录，与原版 ActiveEffects 同属实体存档。
        nbt.putByteArray("MeridianHealingEffects", meridian$effects.encode());
    }
}
