// SPDX-License-Identifier: MIT
#pragma once
namespace displayx {
enum class FenceOutcome {Pending,Complete,Failed};
inline FenceOutcome fenceOutcome(int result,bool readable,bool terminal,bool interrupted) {
    if(result<0)return interrupted?FenceOutcome::Pending:FenceOutcome::Failed;
    if(result>0&&terminal)return FenceOutcome::Failed;
    if(result>0&&readable)return FenceOutcome::Complete;
    return FenceOutcome::Pending;
}
}
