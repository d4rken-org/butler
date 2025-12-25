package eu.darken.butler.common.pkgs

import dagger.Binds
import dagger.Module
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@InstallIn(SingletonComponent::class)
@Module
abstract class SDMaidModule {

    @Binds
    @Reusable
    abstract fun tool(gplay: SDMaidToolGplay): SDMaidTool
}
