package com.eucalyptus.network;

import java.util.List;
import org.apache.log4j.Logger;
import com.eucalyptus.auth.Accounts;
import com.eucalyptus.auth.AuthException;
import com.eucalyptus.auth.Permissions;
import com.eucalyptus.auth.policy.PolicySpec;
import com.eucalyptus.auth.principal.Account;
import com.eucalyptus.auth.principal.User;
import com.eucalyptus.auth.principal.UserFullName;
import com.eucalyptus.cloud.util.DuplicateMetadataException;
import com.eucalyptus.context.Context;
import com.eucalyptus.context.Contexts;
import com.eucalyptus.entities.EntityWrapper;
import com.eucalyptus.records.Logs;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.Types;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import edu.ucsb.eucalyptus.msgs.AuthorizeSecurityGroupIngressResponseType;
import edu.ucsb.eucalyptus.msgs.AuthorizeSecurityGroupIngressType;
import edu.ucsb.eucalyptus.msgs.CreateSecurityGroupResponseType;
import edu.ucsb.eucalyptus.msgs.CreateSecurityGroupType;
import edu.ucsb.eucalyptus.msgs.DeleteSecurityGroupResponseType;
import edu.ucsb.eucalyptus.msgs.DeleteSecurityGroupType;
import edu.ucsb.eucalyptus.msgs.DescribeSecurityGroupsResponseType;
import edu.ucsb.eucalyptus.msgs.DescribeSecurityGroupsType;
import edu.ucsb.eucalyptus.msgs.IpPermissionType;
import edu.ucsb.eucalyptus.msgs.RevokeSecurityGroupIngressResponseType;
import edu.ucsb.eucalyptus.msgs.RevokeSecurityGroupIngressType;
import edu.ucsb.eucalyptus.msgs.SecurityGroupItemType;

public class NetworkGroupManager {
  private static Logger LOG = Logger.getLogger( NetworkGroupManager.class );
  
  public CreateSecurityGroupResponseType create( CreateSecurityGroupType request ) throws EucalyptusCloudException, DuplicateMetadataException {
    Context ctx = Contexts.lookup( );
    NetworkGroups.createDefault( ctx.getUserFullName( ) );
    /**
     * GRZE:WARN: do this /first/, ensure the default group exists to cover some old broken installs
     **/
    if ( !Types.isContextAuthorized( null ) ) {

    }
    String action = PolicySpec.requestToAction( request );
    if ( !ctx.hasAdministrativePrivileges( ) ) {
      if ( !Permissions.isAuthorized( PolicySpec.VENDOR_EC2, PolicySpec.EC2_RESOURCE_SECURITYGROUP, "", ctx.getAccount( ), action, ctx.getUser( ) ) ) {
        throw new EucalyptusCloudException( "Not authorized to create network group for " + ctx.getUser( ) );
      }
      if ( !Permissions.canAllocate( PolicySpec.VENDOR_EC2, PolicySpec.EC2_RESOURCE_SECURITYGROUP, "", action, ctx.getUser( ), 1L ) ) {
        throw new EucalyptusCloudException( "Quota exceeded to create network group for " + ctx.getUser( ) );
      }
    }
    CreateSecurityGroupResponseType reply = ( CreateSecurityGroupResponseType ) request.getReply( );
    NetworkGroup newGroup = NetworkGroups.create( ctx.getUserFullName( ), request.getGroupName( ), request.getGroupDescription( ) );
    try {
      EntityWrapper.get( NetworkGroup.class ).mergeAndCommit( newGroup );
      return reply;
    } catch ( final Exception ex ) {
      Logs.extreme( ).error( ex, ex );
//      if( ex.getCause( ) instanceof  ) {
//        return reply.markFailed( );
//      } else {
      throw new EucalyptusCloudException( "CreatSecurityGroup failed because: " + ex.getMessage( ), ex );
//      }
    }
  }
  
  public DeleteSecurityGroupResponseType delete( DeleteSecurityGroupType request ) throws EucalyptusCloudException {
    Context ctx = Contexts.lookup( );
    NetworkGroups.createDefault( ctx.getUserFullName( ) );//ensure the default group exists to cover some old broken installs
    DeleteSecurityGroupResponseType reply = ( DeleteSecurityGroupResponseType ) request.getReply( );
    if ( Contexts.lookup( ).hasAdministrativePrivileges( ) && request.getGroupName( ).indexOf( "::" ) != -1 ) {
      String[] nameParts = request.getGroupName( ).split( "::" );
      if ( nameParts.length != 2 ) {
        throw new EucalyptusCloudException( "Request to delete group named: " + request.getGroupName( ) + " is malformed." );
      } else {
        String accountId = nameParts[0];
        String groupName = nameParts[1];
        try {
          Account account = Accounts.lookupAccountById( accountId );
          for ( User user : account.getUsers( ) ) {
            UserFullName userFullName = UserFullName.getInstance( user );
            try {
              NetworkGroupUtil.getUserNetworkRulesGroup( userFullName, groupName );
              NetworkGroupUtil.deleteUserNetworkRulesGroup( userFullName, groupName );
            } catch ( EucalyptusCloudException ex ) {
              //need to iterate over all users in the account and check each of their security groups
            }
          }
        } catch ( AuthException ex ) {
          LOG.error( ex.getMessage( ) );
          throw new EucalyptusCloudException( "Deleting security failed because of: " + ex.getMessage( ) + " for request " + request.toSimpleString( ) );
        }
      }
    } else {
      if ( !Permissions.isAuthorized( PolicySpec.VENDOR_EC2, PolicySpec.EC2_RESOURCE_SECURITYGROUP, request.getGroupName( ), ctx.getAccount( ),
                                      PolicySpec.requestToAction( request ), ctx.getUser( ) ) ) {
        throw new EucalyptusCloudException( "Not authorized to delete network group " + request.getGroupName( ) + " for " + ctx.getUser( ) );
      }
      NetworkGroupUtil.deleteUserNetworkRulesGroup( ctx.getUserFullName( ), request.getGroupName( ) );
    }
    reply.set_return( true );
    return reply;
  }
  
  public DescribeSecurityGroupsResponseType describe( final DescribeSecurityGroupsType request ) throws EucalyptusCloudException {
    final Context ctx = Contexts.lookup( );
    NetworkGroups.createDefault( ctx.getUserFullName( ) );//ensure the default group exists to cover some old broken installs
    final List<String> groupNames = request.getSecurityGroupSet( );
    DescribeSecurityGroupsResponseType reply = ( DescribeSecurityGroupsResponseType ) request.getReply( );
    final List<SecurityGroupItemType> replyList = reply.getSecurityGroupInfo( );
    if ( Contexts.lookup( ).hasAdministrativePrivileges( ) ) {
      try {
        for ( SecurityGroupItemType group : Iterables.filter( NetworkGroupUtil.getUserNetworksAdmin( ctx.getUserFullName( ), request.getSecurityGroupSet( ) ),
                                                              new Predicate<SecurityGroupItemType>( ) {
                                                                @Override
                                                                public boolean apply( SecurityGroupItemType arg0 ) {
                                                                  return groupNames.isEmpty( ) || groupNames.contains( arg0.getGroupName( ) );
                                                                }
                                                              } ) ) {
          replyList.add( group );
        }
      } catch ( Exception e ) {
        LOG.debug( e, e );
      }
    } else {
      try {
        for ( SecurityGroupItemType group : Iterables.filter( NetworkGroupUtil.getUserNetworks( ctx.getUserFullName( ), request.getSecurityGroupSet( ) ),
                                                              new Predicate<SecurityGroupItemType>( ) {
                                                                @Override
                                                                public boolean apply( SecurityGroupItemType arg0 ) {
                                                                  if ( !Permissions.isAuthorized( PolicySpec.VENDOR_EC2, PolicySpec.EC2_RESOURCE_SECURITYGROUP,
                                                                                                  arg0.getGroupName( ), ctx.getAccount( ),
                                                                                                  PolicySpec.requestToAction( request ), ctx.getUser( ) ) ) {
                                                                    return false;
                                                                  }
                                                                  return groupNames.isEmpty( ) || groupNames.contains( arg0.getGroupName( ) );
                                                                }
                                                              } ) ) {
          replyList.add( group );
        }
      } catch ( Exception e ) {
        LOG.debug( e, e );
      }
    }
    return reply;
  }
  
  public RevokeSecurityGroupIngressResponseType revoke( RevokeSecurityGroupIngressType request ) throws EucalyptusCloudException {
    Context ctx = Contexts.lookup( );
    NetworkGroups.createDefault( ctx.getUserFullName( ) );//ensure the default group exists to cover some old broken installs
    RevokeSecurityGroupIngressResponseType reply = ( RevokeSecurityGroupIngressResponseType ) request.getReply( );
    NetworkGroup ruleGroup = NetworkGroupUtil.getUserNetworkRulesGroup( ctx.getUserFullName( ), request.getGroupName( ) );
    if ( !ctx.hasAdministrativePrivileges( )
         && !Permissions.isAuthorized( PolicySpec.VENDOR_EC2, PolicySpec.EC2_RESOURCE_SECURITYGROUP, request.getGroupName( ), ctx.getAccount( ),
                                       PolicySpec.requestToAction( request ), ctx.getUser( ) ) ) {
      throw new EucalyptusCloudException( "Not authorized to revoke network group " + request.getGroupName( ) + " for " + ctx.getUser( ) );
    }
    final List<NetworkRule> ruleList = Lists.newArrayList( );
    for ( IpPermissionType ipPerm : request.getIpPermissions( ) ) {
      ruleList.addAll( NetworkGroupUtil.getNetworkRules( ipPerm ) );
    }
    List<NetworkRule> filtered = Lists.newArrayList( Iterables.filter( ruleGroup.getNetworkRules( ), new Predicate<NetworkRule>( ) {
      @Override
      public boolean apply( NetworkRule rule ) {
        for ( NetworkRule r : ruleList ) {
          if ( r.equals( rule ) && r.getNetworkPeers( ).equals( rule.getNetworkPeers( ) ) && r.getIpRanges( ).equals( rule.getIpRanges( ) ) ) {
            return true;
          }
        }
        return false;
      }
    } ) );
    if ( filtered.size( ) == ruleList.size( ) ) {
      try {
        for ( NetworkRule r : filtered ) {
          ruleGroup.getNetworkRules( ).remove( r );
        }
        ruleGroup = EntityWrapper.get( NetworkGroup.class ).mergeAndCommit( ruleGroup );
      } catch ( Exception ex ) {
        Logs.extreme( ).error( ex, ex );
        throw new EucalyptusCloudException( "RevokeSecurityGroupIngress failed because: " + ex.getMessage( ), ex );
      }
      return reply;
    } else if ( request.getIpPermissions( ).size( ) == 1 && request.getIpPermissions( ).get( 0 ).getIpProtocol( ) == null ) {
      //LAME: this is for the query-based clients which send incomplete named-network requests.
      for ( NetworkRule rule : ruleList ) {
        if ( ruleGroup.getNetworkRules( ).remove( rule ) ) {
          reply.set_return( true );
        }
      }
      if ( reply.get_return( ) ) {
        try {
          ruleGroup = EntityWrapper.get( ruleGroup ).mergeAndCommit( ruleGroup );
        } catch ( Exception ex ) {
          Logs.extreme( ).error( ex, ex );
          throw new EucalyptusCloudException( "RevokeSecurityGroupIngress failed because: " + ex.getMessage( ), ex );
        }
      }
      return reply;
    } else {
      return reply.markFailed( );
    }
  }
  
  public AuthorizeSecurityGroupIngressResponseType authorize( AuthorizeSecurityGroupIngressType request ) throws Exception {
    Context ctx = Contexts.lookup( );
    NetworkGroups.createDefault( ctx.getUserFullName( ) );//ensure the default group exists to cover some old broken installs
    AuthorizeSecurityGroupIngressResponseType reply = ( AuthorizeSecurityGroupIngressResponseType ) request.getReply( );
    NetworkGroup ruleGroup = NetworkGroupUtil.getUserNetworkRulesGroup( ctx.getUserFullName( ), request.getGroupName( ) );
    if ( !ctx.hasAdministrativePrivileges( )
         && !Permissions.isAuthorized( PolicySpec.VENDOR_EC2, PolicySpec.EC2_RESOURCE_SECURITYGROUP, request.getGroupName( ), ctx.getAccount( ),
                                       PolicySpec.requestToAction( request ), ctx.getUser( ) ) ) {
      throw new EucalyptusCloudException( "Not authorized to authorize network group " + request.getGroupName( ) + " for " + ctx.getUser( ) );
    }
    final List<NetworkRule> ruleList = Lists.newArrayList( );
    for ( IpPermissionType ipPerm : request.getIpPermissions( ) ) {
      try {
        ruleList.addAll( NetworkGroupUtil.getNetworkRules( ipPerm ) );
      } catch ( IllegalArgumentException ex ) {
        LOG.error( ex.getMessage( ) );
        reply.set_return( false );
        return reply;
      }
    }
    if ( Iterables.any( ruleGroup.getNetworkRules( ), new Predicate<NetworkRule>( ) {
      @Override
      public boolean apply( NetworkRule rule ) {
        for ( NetworkRule r : ruleList ) {
          if ( r.equals( rule ) && r.getNetworkPeers( ).equals( rule.getNetworkPeers( ) ) && r.getIpRanges( ).equals( rule.getIpRanges( ) ) ) {
            return true || !r.isValid( );
          }
        }
        return false;
      }
    } ) ) {
      reply.set_return( false );
      return reply;
    } else {
      ruleGroup.getNetworkRules( ).addAll( ruleList );
      EntityWrapper.get( ruleGroup ).mergeAndCommit( ruleGroup );
      reply.set_return( true );
    }
    return reply;
  }
}
