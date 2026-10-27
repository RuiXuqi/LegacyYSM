package com.elfmcys.yesstevemodel.geckolib3.geo;

import com.elfmcys.yesstevemodel.client.compat.SpectatorCompat;
import com.elfmcys.yesstevemodel.geckolib3.core.IAnimatable;
import com.elfmcys.yesstevemodel.geckolib3.core.controller.AnimationController;
import com.elfmcys.yesstevemodel.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.yesstevemodel.geckolib3.core.util.Color;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.yesstevemodel.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.yesstevemodel.geckolib3.model.provider.data.EntityModelData;
import com.elfmcys.yesstevemodel.geckolib3.util.EModelRenderCycle;
import com.elfmcys.yesstevemodel.geckolib3.util.IRenderCycle;
import com.elfmcys.yesstevemodel.mclib.utils.Interpolations;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityHanging;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class GeoReplacedEntityRenderer<T extends EntityLivingBase, E extends IAnimatable> extends RendererLivingEntity implements IGeoRenderer<T> {
    protected static final Map<Class<? extends IAnimatable>, GeoReplacedEntityRenderer<?, ?>> renderers = new ConcurrentHashMap<>();

    static {
        AnimationController.addModelFetcher((IAnimatable object) -> {
            GeoReplacedEntityRenderer<?, ?> renderer = renderers.get(object.getClass());
            return renderer == null ? null : renderer.getGeoModelProvider();
        });
    }

    protected final AnimatedGeoModel<E> modelProvider;
    protected final List<GeoLayerRenderer> layerRenderers = new ObjectArrayList<>();
    protected E animatable;
    protected E currentAnimatable;
    protected float widthScale = 1;
    protected float heightScale = 1;
    private IRenderCycle currentModelRenderCycle = EModelRenderCycle.INITIAL;

    public GeoReplacedEntityRenderer(
            AnimatedGeoModel<E> modelProvider, E animatable
    ) {
        super(new ModelBiped(0.0F), 0.5F);
        this.setRenderManager(RenderManager.instance);
        this.modelProvider = modelProvider;
        this.animatable = animatable;
        renderers.putIfAbsent(animatable.getClass(), this);
    }

    public final boolean addLayer(GeoLayerRenderer<T, ?> layer) {
        return this.layerRenderers.add(layer);
    }

    @Override
    public void doRender(
            @Nonnull Entity entity,
            double x, double y, double z,
            float entityYaw, float partialTick
    ) {
        this.doRender((T) entity, x, y, z, entityYaw, partialTick);
    }

    @Override
    public void doRender(
            @Nonnull EntityLivingBase entity,
            double x, double y, double z,
            float entityYaw, float partialTick
    ) {
        this.render((T) entity, x, y, z, entityYaw, partialTick);
    }

    public void render(
            @Nonnull T entity,
            double x, double y, double z,
            float entityYaw, float partialTick
    ) {
        this.render(entity, this.animatable, x, y, z, entityYaw, partialTick);
    }

    public void render(
            @Nonnull T entity, E animatable,
            double x, double y, double z,
            float entityYaw, float partialTick
    ) {
        this.currentAnimatable = animatable;
        /// {@link net.minecraft.client.renderer.entity.RenderPlayer#doRender(AbstractClientPlayer, double, double, double, float, float)}
        if (entity instanceof EntityPlayer player) y -= player.yOffset;
        boolean shouldSit = entity.isRiding() && (entity.ridingEntity != null && entity.ridingEntity.shouldRiderSit());

        this.setCurrentModelRenderCycle(EModelRenderCycle.INITIAL);

        if (MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Pre(entity, this, x, y, z))) {
            return;
        }

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_CULL_FACE);
        this.renderLivingAt(entity, x, y, z);

        EntityModelData entityModelData = new EntityModelData();
        entityModelData.isSitting = shouldSit;
        entityModelData.isChild = entity.isChild();

        float lerpBodyRot = Interpolations.lerpYaw(entity.prevRenderYawOffset, entity.renderYawOffset, partialTick);
        float lerpHeadRot = Interpolations.lerpYaw(entity.prevRotationYawHead, entity.rotationYawHead, partialTick);
        float netHeadYaw = lerpHeadRot - lerpBodyRot;

        /// {@link RendererLivingEntity#doRender(EntityLivingBase, double, double, double, float, float)}
        if (shouldSit && entity.ridingEntity instanceof EntityLivingBase vehicle) {
            lerpBodyRot = Interpolations.lerpYaw(vehicle.prevRenderYawOffset, vehicle.renderYawOffset, partialTick);
            netHeadYaw = lerpHeadRot - lerpBodyRot;
            float clampedHeadYaw = MathHelper.clamp_float(MathHelper.wrapAngleTo180_float(netHeadYaw), -85, 85);
            lerpBodyRot = lerpHeadRot - clampedHeadYaw;
            if (clampedHeadYaw * clampedHeadYaw > 2500f) {
                lerpBodyRot += clampedHeadYaw * 0.2f;
            }
            netHeadYaw = lerpHeadRot - lerpBodyRot;
        }

        float lerpedAge = this.handleRotationFloat(entity, partialTick);
        this.rotateCorpse(entity, lerpedAge, lerpBodyRot, partialTick);

        float limbSwingAmount = 0.0F;
        float limbSwing = 0.0F;
        if (!shouldSit && entity.isEntityAlive()) {
            limbSwingAmount = Math.min(1, Interpolations.lerp(entity.prevLimbSwingAmount, entity.limbSwingAmount, partialTick));
            limbSwing = entity.limbSwing - entity.limbSwingAmount * (1 - partialTick);
            if (entity.isChild()) {
                limbSwing *= 3.0F;
            }
        }

        float headPitch = Interpolations.lerp(entity.prevRotationPitch, entity.rotationPitch, partialTick);
        entityModelData.headPitch = -headPitch;
        entityModelData.netHeadYaw = -MathHelper.clamp_float(MathHelper.wrapAngleTo180_float(netHeadYaw), -85, 85);
        GeoModel model = this.modelProvider.getModel(this.modelProvider.getModelLocation(animatable));
        AnimationEvent<E> predicate = new AnimationEvent<>(animatable, limbSwing, limbSwingAmount, partialTick,
                (limbSwingAmount <= -this.getSwingMotionAnimThreshold() || limbSwingAmount <= this.getSwingMotionAnimThreshold()), Collections.singletonList(entityModelData));

        this.modelProvider.setCustomAnimations(animatable, this.getInstanceId(entity), predicate);

        Minecraft mc = Minecraft.getMinecraft();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glTranslatef(0, 0.01f, 0);
        boolean mainBrightness = this.setDoRenderBrightness(entity, partialTick);

        Color renderColor = this.getRenderColor(entity, partialTick);
        try {
            boolean isVisible = !entity.isInvisible();
            boolean isGhost = !isVisible && !entity.isInvisibleToPlayer(mc.thePlayer);
            if (isVisible || isGhost) {
                this.bindEntityTexture(entity);
                if (isGhost) {
                    // GlStateManager.Profile.TRANSPARENT_MODEL
                    GL11.glPushMatrix();
                    GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.15F);
                    GL11.glDepthMask(false);
                    GL11.glEnable(GL11.GL_BLEND);
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                    GL11.glAlphaFunc(GL11.GL_GREATER, 1.0F / 255.0F);
                }
                this.render(model, entity, partialTick,
                        (float) renderColor.getRed() / 255f, (float) renderColor.getGreen() / 255f,
                        (float) renderColor.getBlue() / 255f, (float) renderColor.getAlpha() / 255);
                if (isGhost) {
                    GL11.glDisable(GL11.GL_BLEND);
                    GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
                    GL11.glPopMatrix();
                    GL11.glDepthMask(true);
                }
            }
        } finally {
            if (mainBrightness) this.unsetBrightness();
        }

        GL11.glDepthMask(true);

        if (!(entity instanceof EntityPlayer player) || !SpectatorCompat.isSpectator(player)) {
            for (GeoLayerRenderer layerRenderer : this.layerRenderers) {
                boolean layerBrightness = this.setBrightness(entity, partialTick, layerRenderer.shouldCombineTextures());
                try {
                    layerRenderer.render(
                            entity, limbSwing, limbSwingAmount,
                            partialTick, lerpedAge,
                            netHeadYaw, headPitch, renderColor
                    );
                } finally {
                    if (layerBrightness) this.unsetBrightness();
                }
            }
        }

        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glPopMatrix();

        if (this.renderManager.livingPlayer != null) {
            this.passSpecialRender(entity, x, y, z);
        }

        /// {@link net.minecraft.client.renderer.entity.RenderLiving#doRender(EntityLiving, double, double, double, float, float)}
        if (entity instanceof EntityLiving mob) {
            Entity leashHolder = mob.getLeashedToEntity();
            if (leashHolder != null) {
                this.renderLeash(mob, x, y, z, entityYaw, partialTick, leashHolder);
            }
        }
        MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Post(entity, this, x, y, z));
    }

//    /**
//     * Returns where in the swing animation the living entity is (from 0 to 1). Args
//     * : entity, partialTickTime
//     */
//    protected float getSwingProgress(EntityLivingBase livingBase, float partialTickTime) {
//        return livingBase.getSwingProgress(partialTickTime);
//    }

    protected float getSwingMotionAnimThreshold() {
        return 0.15f;
    }

    /**
     * {@link net.minecraft.client.renderer.entity.RenderPlayer#renderLivingAt(AbstractClientPlayer, double, double, double)}
     */
    @SuppressWarnings("JavadocReference")
    @Override
    protected void renderLivingAt(@Nonnull EntityLivingBase entity, double x, double y, double z) {
        if (entity instanceof EntityPlayer player && player.isEntityAlive() && player.isPlayerSleeping()) {
            // renderOffsetX, renderOffsetY, renderOffsetZ
            super.renderLivingAt(entity, x + player.field_71079_bU, y + player.field_71082_cx, z + player.field_71089_bV);
        } else {
            super.renderLivingAt(entity, x, y, z);
        }
    }

    /**
     * {@link net.minecraft.client.renderer.entity.RenderPlayer#rotateCorpse(AbstractClientPlayer, float, float, float)}
     */
    @SuppressWarnings("JavadocReference")
    @Override
    protected void rotateCorpse(@Nonnull EntityLivingBase entity, float ageInTicks, float rotationYaw, float partialTicks) {
        if (entity instanceof EntityPlayer player && entity.isEntityAlive() && entity.isPlayerSleeping()) {
            GL11.glRotatef(player.getBedOrientationInDegrees(), 0, 1, 0);
            GL11.glRotatef(this.getDeathMaxRotation(entity), 0, 0, 1);
            GL11.glRotatef(270.0F, 0, 1, 0);
            return;
        }
        int deathTime = entity.deathTime;
        entity.deathTime = 0;
        super.rotateCorpse(entity, ageInTicks, rotationYaw, partialTicks);
        entity.deathTime = deathTime;
    }

    /**
     * {@link net.minecraft.client.renderer.entity.RenderLiving#func_110827_b(EntityLiving, double, double, double, float, float)}
     */
    @SuppressWarnings("JavadocReference")
    protected <TEntity extends Entity> void renderLeash(
            EntityLiving entity, double x, double y, double z,
            float entityYaw, float partialTicks, @Nonnull TEntity leashHolder
    ) {
        y = y - (1.6D - (double) entity.height) * 0.5D;
        Tessellator tessellator = Tessellator.instance;

        double holderYawRad = Interpolations.lerp(leashHolder.prevRotationYaw, leashHolder.rotationYaw, partialTicks * 0.5F) * 0.01745329238474369D;
        double holderPitchRad = Interpolations.lerp(leashHolder.prevRotationPitch, leashHolder.rotationPitch, partialTicks * 0.5F) * 0.01745329238474369D;
        double cosHolderYaw = Math.cos(holderYawRad);
        double sinHolderYaw = Math.sin(holderYawRad);
        double sinHolderPitch = Math.sin(holderPitchRad);
        if (leashHolder instanceof EntityHanging) {
            cosHolderYaw = 0.0D;
            sinHolderYaw = 0.0D;
            sinHolderPitch = -1.0D;
        }
        double cosHolderPitch = Math.cos(holderPitchRad);

        double holderX = Interpolations.lerp(leashHolder.prevPosX, leashHolder.posX, partialTicks)
                - cosHolderYaw * 0.7D - sinHolderYaw * 0.5D * cosHolderPitch;
        double holderY = Interpolations.lerp(leashHolder.prevPosY + (double) leashHolder.getEyeHeight() * 0.7D,
                leashHolder.posY + (double) leashHolder.getEyeHeight() * 0.7D, partialTicks)
                - sinHolderPitch * 0.5D - 0.25D;
        double holderZ = Interpolations.lerp(leashHolder.prevPosZ, leashHolder.posZ, partialTicks)
                - sinHolderYaw * 0.7D + cosHolderYaw * 0.5D * cosHolderPitch;

        double entityYawRad = Interpolations.lerp(entity.prevRenderYawOffset, entity.renderYawOffset, partialTicks) * 0.01745329238474369D + (Math.PI / 2D);

        double neckOffsetX = Math.cos(entityYawRad) * (double) entity.width * 0.4D;
        double neckOffsetZ = Math.sin(entityYawRad) * (double) entity.width * 0.4D;
        double entityNeckX = Interpolations.lerp(entity.prevPosX, entity.posX, partialTicks) + neckOffsetX;
        double entityNeckY = Interpolations.lerp(entity.prevPosY, entity.posY, partialTicks);
        double entityNeckZ = Interpolations.lerp(entity.prevPosZ, entity.posZ, partialTicks) + neckOffsetZ;
        x += neckOffsetX;
        z += neckOffsetZ;
        double diffX = (float) (holderX - entityNeckX);
        double diffY = (float) (holderY - entityNeckY);
        double diffZ = (float) (holderZ - entityNeckZ);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);

        // 拴绳的一个面
        tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
        for (int segment = 0; segment <= 24; ++segment) {
            // 初始颜色
            float r = 0.5F;
            float g = 0.4F;
            float b = 0.3F;

            // 偶数段颜色减深
            if (segment % 2 == 0) {
                r *= 0.7F;
                g *= 0.7F;
                b *= 0.7F;
            }

            float progress = (float) segment / 24.0F;
            double hangOffset = progress * progress + progress;
            double yOffset = (24.0F - (float) segment) / 18.0F + 0.125F;

            tessellator.setColorRGBA_F(r, g, b, 1.0F);
            tessellator.addVertex(
                    x + diffX * (double) progress + 0.0D,
                    y + diffY * hangOffset * 0.5D + yOffset,
                    z + diffZ * (double) progress
            );
            tessellator.setColorRGBA_F(r, g, b, 1.0F);
            tessellator.addVertex(
                    x + diffX * (double) progress + 0.025D,
                    y + diffY * hangOffset * 0.5D + yOffset + 0.025D,
                    z + diffZ * (double) progress
            );
        }
        tessellator.draw();

        // 拴绳的另一个面
        tessellator.startDrawing(GL11.GL_TRIANGLE_STRIP);
        for (int segment = 0; segment <= 24; ++segment) {
            float r = 0.5F;
            float g = 0.4F;
            float b = 0.3F;

            if (segment % 2 == 0) {
                r *= 0.7F;
                g *= 0.7F;
                b *= 0.7F;
            }

            float progress = (float) segment / 24.0F;
            double hangOffset = progress * progress + progress;
            double yOffset = (24.0F - (float) segment) / 18.0F + 0.125F;

            tessellator.setColorRGBA_F(r, g, b, 1.0F);
            tessellator.addVertex(
                    x + diffX * (double) progress + 0.0D,
                    y + diffY * hangOffset * 0.5D + yOffset + 0.025D,
                    z + diffZ * (double) progress
            );
            tessellator.setColorRGBA_F(r, g, b, 1.0F);
            tessellator.addVertex(
                    x + diffX * (double) progress + 0.025D,
                    y + diffY * hangOffset * 0.5D + yOffset,
                    z + diffZ * (double) progress + 0.025D
            );
        }
        tessellator.draw();

        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    protected static final FloatBuffer brightnessBuffer = GLAllocation.createDirectFloatBuffer(4); // 4 个通道
    protected static final DynamicTexture TEXTURE_BRIGHTNESS = new DynamicTexture(16, 16);

    static {
        int[] aint = TEXTURE_BRIGHTNESS.getTextureData();
        for (int i = 0; i < 256; ++i) {
            aint[i] = -1;
        }
        TEXTURE_BRIGHTNESS.updateDynamicTexture();
    }

    /**
     * {@link RenderLivingBase#setDoRenderBrightness(EntityLivingBase, float)}
     */
    @SuppressWarnings("JavadocReference")
    protected boolean setDoRenderBrightness(EntityLivingBase entityLivingBaseIn, float partialTicks) {
        return this.setBrightness(entityLivingBaseIn, partialTicks, true);
    }

    /**
     * 渲染受击时的红光<br>
     * {@link RenderLivingBase#setBrightness(EntityLivingBase, float, boolean)}
     */
    @SuppressWarnings("JavadocReference")
    protected boolean setBrightness(EntityLivingBase entitylivingbaseIn, float partialTicks, boolean combineTextures) {
        float brightness = entitylivingbaseIn.getBrightness(partialTicks);
        int colorMultiplier = this.getColorMultiplier(entitylivingbaseIn, brightness, partialTicks);
        boolean hasCustomColor = (colorMultiplier >> 24 & 255) > 0;
        boolean isHurtOrDying = entitylivingbaseIn.hurtTime > 0 || entitylivingbaseIn.deathTime > 0;
        if (!hasCustomColor && (!isHurtOrDying || !combineTextures)) return false;

        // The 1.8 brightness combiner must not escape into 1.7.10 world or GUI rendering.
        GL11.glPushAttrib(GL11.GL_TEXTURE_BIT | GL11.GL_CURRENT_BIT);

        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL11.GL_MODULATE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, OpenGlHelper.defaultTexUnit);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE1_RGB, GL13.GL_PRIMARY_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND1_RGB, GL11.GL_SRC_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_ALPHA, GL11.GL_REPLACE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_ALPHA, OpenGlHelper.defaultTexUnit);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL13.GL_INTERPOLATE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, GL13.GL_CONSTANT);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE1_RGB, GL13.GL_PREVIOUS);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE2_RGB, GL13.GL_CONSTANT);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND1_RGB, GL11.GL_SRC_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND2_RGB, GL11.GL_SRC_ALPHA);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_ALPHA, GL11.GL_REPLACE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_ALPHA, GL13.GL_PREVIOUS);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
        brightnessBuffer.position(0);

        if (isHurtOrDying) {
            brightnessBuffer.put(1.0F); // R
            brightnessBuffer.put(0.0F); // G
            brightnessBuffer.put(0.0F); // B
            brightnessBuffer.put(0.3F); // A
        } else {
            float red = (float) (colorMultiplier >> 16 & 255) / 255.0F;
            float green = (float) (colorMultiplier >> 8 & 255) / 255.0F;
            float blue = (float) (colorMultiplier & 255) / 255.0F;
            float alpha = (float) (colorMultiplier >> 24 & 255) / 255.0F;
            brightnessBuffer.put(red);
            brightnessBuffer.put(green);
            brightnessBuffer.put(blue);
            brightnessBuffer.put(1.0F - alpha);
        }

        brightnessBuffer.flip();
        GL11.glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, brightnessBuffer);
        OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE2);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, TEXTURE_BRIGHTNESS.getGlTextureId());
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL11.GL_MODULATE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, GL13.GL_PREVIOUS);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE1_RGB, OpenGlHelper.lightmapTexUnit);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND1_RGB, GL11.GL_SRC_COLOR);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_ALPHA, GL11.GL_REPLACE);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_ALPHA, GL13.GL_PREVIOUS);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        return true;
    }

    /**
     * {@link RenderLivingBase#unsetBrightness()}
     */
    @SuppressWarnings("JavadocReference")
    protected void unsetBrightness() {
        GL11.glPopAttrib();
    }

    @Override
    public AnimatedGeoModel getGeoModelProvider() {
        return this.modelProvider;
    }

    @Override
    @Nonnull
    public IRenderCycle getCurrentModelRenderCycle() {
        return this.currentModelRenderCycle;
    }

    @Override
    public void setCurrentModelRenderCycle(IRenderCycle currentModelRenderCycle) {
        this.currentModelRenderCycle = currentModelRenderCycle;
    }

    @Override
    public float getWidthScale(T entity) {
        return this.widthScale;
    }

    @Override
    public float getHeightScale(T entity) {
        return this.heightScale;
    }

    @Nullable
    @Override
    public ResourceLocation getEntityTexture(@Nonnull Entity entity) {
        return this.getTextureLocation((T) entity);
    }

    @Nullable
    public ResourceLocation getTextureLocation(@Nonnull T entity) {
        return this.modelProvider.getTextureLocation(this.currentAnimatable);
    }
}
